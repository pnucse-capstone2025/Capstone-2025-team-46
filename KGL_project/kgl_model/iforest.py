# -*- coding: utf-8 -*-
# fastapi_main.py (수정된 코드 - 안정화 버전)

import os
import json
import pickle
import numpy as np
from datetime import datetime, timezone
from collections import deque
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler
import logging
from pathlib import Path
from fastapi import FastAPI
from pydantic import BaseModel
from typing import List, Dict, Any, Optional

# ----------------------
# 로깅 설정
# ----------------------
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("AnomalyDetector")


# ----------------------
# 머신러닝 로직 클래스 (API 요청에 맞게 수정됨)
# ----------------------
class AnomalyDetector:
    """
    - API 요청을 받아 단일 로그에 대한 이상 탐지 추론을 수행하는 클래스
    - 데이터 수집, 모델 학습, 추론 과정을 포함합니다.
    """

    def __init__(
            self,
            model_path="./models",
            initial_samples={
                "sensor": 500,
                "touch_drag": 200,
                "touch_pressure": 200,
                },
            contamination=0.03,
            retrain_interval=500,
            n_estimators=200,
            anomaly_percentile=0.005,
            margin=0.002
    ):
        self.model_path = Path(model_path)
        self.model_path.mkdir(parents=True, exist_ok=True)
        self.modalities = ["sensor", "touch_drag", "touch_pressure"]
        self.initial_samples = initial_samples
        self.contamination = contamination
        self.retrain_interval = retrain_interval
        self.n_estimators = n_estimators
        self.anomaly_percentile = anomaly_percentile
        self.margin = margin
        self.models = {m: None for m in self.modalities}
        self.scalers = {m: StandardScaler() for m in self.modalities}
        self.thresholds = {m: None for m in self.modalities}
        self.recent_data = {m: deque(maxlen=self.retrain_interval + self.initial_samples[m]) for m in self.modalities}
        self.infer_counts = {m: 0 for m in self.modalities}
        self.modes = {m: "collecting" for m in self.modalities}

        for m in self.modalities:
            self._initialize_modality(m)

    def _model_files(self, modality: str):
        """모델 파일 경로를 반환합니다."""
        mdir = self.model_path / modality
        mdir.mkdir(parents=True, exist_ok=True)
        return (
            mdir / f"isolation_forest_{modality}.pkl",
            mdir / f"scaler_{modality}.pkl",
            mdir / f"meta_{modality}.json",
        )

    def _initialize_modality(self, modality: str):
        """초기화 시 기존 모델을 로드하거나 데이터 수집 모드로 전환합니다."""
        model_file, scaler_file, meta_file = self._model_files(modality)
        if model_file.exists() and scaler_file.exists():
            try:
                with open(model_file, 'rb') as f:
                    self.models[modality] = pickle.load(f)
                with open(scaler_file, 'rb') as f:
                    self.scalers[modality] = pickle.load(f)

                # 로드된 스케일러가 유효한지 확인
                if not hasattr(self.scalers[modality], 'mean_'):
                    raise AttributeError("스케일러에 'mean_' 속성이 없어 유효하지 않습니다.")

                if meta_file.exists():
                    try:
                        meta = json.loads(meta_file.read_text(encoding="utf-8"))
                        self.thresholds[modality] = meta.get("threshold", None)
                        logger.info(f"[{modality}] 임계값 로드: {self.thresholds[modality]}")
                    except Exception as me:
                        logger.warning(f"[{modality}] 메타 로드 실패: {me}")
                self.modes[modality] = "inference"
                logger.info(f"[{modality}] 기존 모델 로드 -> 추론 모드")
            except Exception as e:
                logger.error(f"[{modality}] 모델/스케일러 로드 실패: {e}")
                self.modes[modality] = "collecting"
                logger.info(f"[{modality}] 새 데이터 수집 시작")
        else:
            logger.info(f"[{modality}] 기존 모델 없음 -> {self.initial_samples[modality]}개 수집 후 학습")

    def _save_model_and_meta(self, modality: str, threshold: float):
        """학습된 모델과 메타데이터를 저장합니다."""
        model_file, scaler_file, meta_file = self._model_files(modality)
        try:
            with open(model_file, 'wb') as f:
                pickle.dump(self.models[modality], f)
            with open(scaler_file, 'wb') as f:
                pickle.dump(self.scalers[modality], f)
            meta = {
                "modality": modality,
                "saved_at": datetime.now(timezone.utc).isoformat(),
                "threshold": float(threshold),
                "anomaly_percentile": self.anomaly_percentile,
                "margin": self.margin,
                "contamination": self.contamination,
                "n_estimators": self.n_estimators
            }
            meta_file.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
            logger.info(f"[{modality}] 모델/스케일러/메타 저장 완료")
        except Exception as e:
            logger.error(f"[{modality}] 모델/메타 저장 실패: {e}")

    def _train_model(self, modality: str):
        """모델 학습 및 임계값 설정"""
        data = np.array(self.recent_data[modality])

        if data.shape[0] < self.initial_samples[modality]:
            logger.info(f"[{modality}] 데이터 부족 ({data.shape[0]} / {self.initial_samples[modality]}), 학습을 건너뜁니다.")
            return False

        try:
            logger.info(f"[{modality}] 모델 학습 시작. 데이터 수: {data.shape[0]}")

            # StandardScaler는 fit_transform으로 한 번에 처리
            X_scaled = self.scalers[modality].fit_transform(data)

            # 모델 학습
            model = IsolationForest(
                n_estimators=self.n_estimators,
                contamination=self.contamination,
                random_state=42,
                n_jobs=-1
            )
            model.fit(X_scaled)
            self.models[modality] = model

            # 임계값 계산 (결정 함수 점수 사용)
            scores = model.decision_function(X_scaled)
            threshold = np.percentile(scores, self.anomaly_percentile * 100)
            self.thresholds[modality] = threshold
            logger.info(f"[{modality}] 학습 완료. 임계값: {threshold}")

            # 모델/스케일러 저장
            self._save_model_and_meta(modality, threshold)

            # 모든 과정이 성공했을 때만 모드를 전환
            self.modes[modality] = "inference"
            return True

        except Exception as e:
            logger.error(f"[{modality}] 모델 학습 또는 저장 중 오류 발생: {e}")
            self.modes[modality] = "collecting"  # 오류 발생 시 수집 모드로 유지
            return False

    def _retrain_if_due(self, modality: str):
        """재학습 필요 시 호출"""
        if self.infer_counts[modality] >= self.retrain_interval:
            logger.info(f"[{modality}] {self.retrain_interval}회 추론 완료. 재학습을 시작합니다.")
            self.infer_counts[modality] = 0
            # 재학습을 위해 데이터가 충분히 쌓일 때까지 기다림
            self._train_model(modality)

    def _predict_one(self, modality: str, features: np.ndarray):
        """단일 데이터에 대한 추론"""
        # 모델과 스케일러가 모두 유효한지 다시 한번 확인
        if self.modes[modality] != "inference" or self.models[modality] is None or not hasattr(self.scalers[modality],
                                                                                               'mean_'):
            logger.warning(f"[{modality}] 모델 또는 스케일러가 유효하지 않습니다. 추론을 건너뜁니다.")
            return None, 0.0

        try:
            Xs = self.scalers[modality].transform(features.reshape(1, -1))
            score = float(self.models[modality].decision_function(Xs)[0])
            thr = self.thresholds.get(modality)
            if thr is None:
                pred = int(self.models[modality].predict(Xs)[0])
                is_anom = (pred == -1)
            else:
                is_anom = (score <= (thr - self.margin))

            self.infer_counts[modality] += 1
            self._retrain_if_due(modality)

            return (-1 if is_anom else 1), score
        except Exception as e:
            logger.error(f"[{modality}] 추론 중 오류 발생: {e}")
            # 오류 발생 시 기본값 반환
            return None, 0.0

    def predict_single_log(self, log: Dict[str, Any]) -> Dict[str, Any]:
        action_type = log.get("action_type")
        modality = "unknown"
        feats = None

        # 1. 피처 추출 및 모달리티 결정
        if action_type.startswith("sensor_"):
            modality = "sensor"
            feats = self._extract_sensor_features_from_dict(log.get("params"))
        elif "drag" in action_type:
            modality = "touch_drag"
            feats = self._extract_touch_drag_features_from_dict(log.get("params"))
        elif "touch_pressure" in action_type:
            modality = "touch_pressure"
            feats = self._extract_touch_pressure_features_from_dict(log.get("params"))

        if feats is None or modality == "unknown":
            return {
                "modality": "unknown",
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "anomaly_score": 0.0,
                "is_anomaly": False,
            }

        # 2. 데이터 수집 또는 추론
        current_mode = self.modes[modality]

        if current_mode == "collecting":
            self.recent_data[modality].append(feats)
            logger.info(f"[{modality}] 데이터 수집 중... ({len(self.recent_data[modality])} / {self.initial_samples[modality]})")
            if len(self.recent_data[modality]) >= self.initial_samples[modality]:
                logger.info(f"[{modality}] {self.initial_samples[modality]}개 수집 완료 → 모델 학습 시작")
                self._train_model(modality)

            return {
                "modality": modality,
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "anomaly_score": 0.0,
                "is_anomaly": False,
            }

        elif current_mode == "inference":
            pred, score = self._predict_one(modality, feats)
            return {
                "modality": modality,
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "anomaly_score": float(score),
                "is_anomaly": bool(pred == -1),
            }

        # 기타 알 수 없는 경우
        return {
            "modality": "unknown",
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "anomaly_score": 0.0,
            "is_anomaly": False,
        }

    # ... (피처 추출 메서드들은 동일)
    def _extract_sensor_features_from_dict(self, params: Dict[str, Any]):
        if not params:
            return np.zeros(3, dtype=float)
        x = params.get('x', 0.0)
        y = params.get('y', 0.0)
        z = params.get('z', 0.0)
        return np.array([x, y, z], dtype=float)

    def _extract_touch_drag_features_from_dict(self, params: Dict[str, Any]):
        if not params:
            return np.zeros(9, dtype=float)

        duration = params.get('duration', 0.0)
        total_distance = params.get('total_distance', 0.0)
        straightness = params.get('straightness', 0.0)
        move_count = params.get('move_count', 0.0)

        velocity = (total_distance / (duration / 1000.0)) if duration > 0 else 0.0

        dirs = str(params.get('drag_direction', '')).lower()
        dir_down = 1.0 if dirs == 'down' else 0.0
        dir_up = 1.0 if dirs == 'up' else 0.0
        dir_left = 1.0 if dirs == 'left' else 0.0
        dir_right = 1.0 if dirs == 'right' else 0.0

        return np.array(
            [duration, total_distance, velocity, straightness, move_count, dir_down, dir_up, dir_left, dir_right],
            dtype=float)

    def _extract_touch_pressure_features_from_dict(self, params: Dict[str, Any]):
        if not params:
            return np.zeros(4, dtype=float)

        duration = params.get('touch_duration', 0.0)
        size = params.get('size', 0.0)
        x = params.get('x', 0.0)
        y = params.get('y', 0.0)

        return np.array([duration, size, x, y], dtype=float)


# ----------------------
# FastAPI 서버
# ----------------------
app = FastAPI()
detector = AnomalyDetector()


class PredictPayload(BaseModel):
    user_id: str
    session_id: str
    action_type: str
    sequence_index: int
    timestamp: str
    params: Dict[str, Any]
    device_info: Dict[str, Any]
    location: Optional[Dict[str, Any]]


@app.post("/predict")
def predict_anomaly(payload: PredictPayload):
    try:
        result = detector.predict_single_log(payload.dict())
        return result
    except Exception as e:
        logger.error(f"예측 중 오류 발생: {e}")
        return {"error": str(e)}, 500
