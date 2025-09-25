# main.py
import os
import json
import pickle
import joblib
import numpy as np
from datetime import datetime, timezone
from collections import deque
from pathlib import Path
from typing import List, Dict, Any, Optional

import logging
from fastapi import FastAPI
from pydantic import BaseModel

import pandas as pd
import torch
import torch.nn as nn
import torch.optim as optim
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler


logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("AnomalyDetector")


def _json_safeload_one_file(p):
    try:
        with open(p, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        objs = []
        try:
            with open(p, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        objs.append(json.loads(line))
                    except Exception:
                        pass
        except Exception:
            pass
        return objs if objs else None

def load_json_logs(path, filename_contains=None):
    paths = []
    if os.path.isfile(path):
        base = os.path.basename(path)
        if (filename_contains is None) or (filename_contains in base):
            paths.append(path)
    else:
        for root, _, files in os.walk(path):
            for file in files:
                if not file.lower().endswith((".json", ".log")):
                    continue
                if filename_contains and filename_contains not in file:
                    continue
                paths.append(os.path.join(root, file))
    all_data = []
    for p in paths:
        data = _json_safeload_one_file(p)
        if data is None:
            continue
        if isinstance(data, list):
            all_data.extend(data)
        else:
            all_data.append(data)
    return all_data


def parse_touch(logs: List[Dict[str, Any]]) -> pd.DataFrame:
    rows = []
    if not isinstance(logs, list):
        return pd.DataFrame()
    for idx, item in enumerate(logs):
        if not isinstance(item, dict):
            continue
        p = item.get("params", {})
        if not isinstance(p, dict):
            p = {}
        ts_val = p.get("timestamp") or item.get("ts") or item.get("timestamp") or idx

        def gv(k, default=np.nan):
            return p.get(k, item.get(k, default))

        event = p.get("event_type") or item.get("action_type") or item.get("event_type") or ""
        rows.append({
            "ts": ts_val,
            "touch_event": str(event).lower(),
            "touch_x": gv("x"),
            "touch_y": gv("y"),
            "touch_size": gv("size"),
            "touch_pressure": gv("pressure"),
            "start_x": gv("start_x"),
            "start_y": gv("start_y"),
            "end_x": gv("end_x"),
            "end_y": gv("end_y"),
            "total_distance": gv("total_distance"),
            "duration": gv("duration"),
            "move_count": gv("move_count"),
            "drag_direction": gv("drag_direction", None),
        })
    if not rows:
        return pd.DataFrame()

    df = pd.DataFrame(rows)
    df["ts"] = pd.to_numeric(df["ts"], errors="coerce")
    df["touch_x"] = pd.to_numeric(df.get("touch_x"), errors="coerce")
    df["touch_y"] = pd.to_numeric(df.get("touch_y"), errors="coerce")
    df["ts"] = df["ts"].fillna(0).astype(np.int64)

    if "touch_x" in df.columns and "touch_y" in df.columns:
        dx = df["touch_x"].diff()
        dy = df["touch_y"].diff()
        df["dx"] = dx.fillna(0.0).astype(float)
        df["dy"] = dy.fillna(0.0).astype(float)
        df["speed"] = np.sqrt(df["dx"] ** 2 + df["dy"] ** 2)
    else:
        df["dx"], df["dy"], df["speed"] = 0.0, 0.0, 0.0

    return df

def parse_sensor(logs: List[Dict[str, Any]]) -> pd.DataFrame:

    rows = []
    if not isinstance(logs, list):
        return pd.DataFrame()
    for idx, item in enumerate(logs):
        if not isinstance(item, dict):
            continue
        p = item.get("params", {}) if isinstance(item.get("params"), dict) else {}
        ts_val = p.get("timestamp") or item.get("ts") or item.get("timestamp") or idx
        ttype = (p.get("type") or item.get("type") or "unknown").lower()
        rows.append({
            "ts": ts_val,
            "type": ttype,
            "x": p.get("x", item.get("x", np.nan)),
            "y": p.get("y", item.get("y", np.nan)),
            "z": p.get("z", item.get("z", np.nan)),
        })
    if not rows:
        return pd.DataFrame()
    df = pd.DataFrame(rows)
    df["ts"] = pd.to_numeric(df["ts"], errors="coerce").fillna(0).astype(np.int64)
    for c in ("x", "y", "z"):
        df[c] = pd.to_numeric(df[c], errors="coerce")

    if "type" in df.columns and df["type"].notna().any():
        pivot_df = df.pivot_table(index="ts", columns="type", values=["x", "y", "z"], aggfunc="mean")
        pivot_df.columns = [f"{col[1]}_{col[0]}" for col in pivot_df.columns]
        pivot_df = pivot_df.sort_index(axis=1)
        return pivot_df.reset_index()
    else:
        return df.groupby("ts", as_index=False).mean(numeric_only=True)

def parse_sensor_sequence_for_lstm(logs: List[Dict[str, Any]]) -> pd.DataFrame:

    rows = []
    if not isinstance(logs, list):
        return pd.DataFrame()
    for item in logs:
        if not isinstance(item, dict):
            continue
        p = item.get("params", {}) if isinstance(item.get("params"), dict) else {}
        x = p.get("x", item.get("x", np.nan))
        y = p.get("y", item.get("y", np.nan))
        z = p.get("z", item.get("z", np.nan))
        rows.append({"x": x, "y": y, "z": z})
    if not rows:
        return pd.DataFrame()
    df = pd.DataFrame(rows)
    for c in ("x", "y", "z"):
        df[c] = pd.to_numeric(df[c], errors="coerce").fillna(0.0)
    return df

def create_sequences(arr: np.ndarray, seq_len: int) -> np.ndarray:
    if arr is None or len(arr) < 2 or seq_len is None or seq_len < 2:
        return np.array([])
    if len(arr) < seq_len:
        return np.array([])
    seqs = [arr[i : i + seq_len] for i in range(len(arr) - seq_len + 1)]
    return np.array(seqs) if seqs else np.array([])


class LSTMAutoencoder(nn.Module):
    def __init__(self, feature_dim, hidden_dim=64, latent_dim=32, num_layers=1):
        super().__init__()
        self.enc = nn.LSTM(feature_dim, hidden_dim, num_layers=num_layers, batch_first=True)
        self.to_z = nn.Linear(hidden_dim, latent_dim)
        self.from_z = nn.Linear(latent_dim, hidden_dim)
        self.dec = nn.LSTM(feature_dim, hidden_dim, num_layers=num_layers, batch_first=True)
        self.out_proj = nn.Linear(hidden_dim, feature_dim)

    def forward(self, x):
        h, _ = self.enc(x)
        z = self.to_z(h[:, -1, :])
        h0 = self.from_z(z).unsqueeze(0)
        c0 = torch.zeros_like(h0)
        dec_in = torch.zeros_like(x)
        y, _ = self.dec(dec_in, (h0, c0))
        return self.out_proj(y)

def load_lstm_bundle(model_path: Path, feature_dim: int, device: str):
    bundle = torch.load(model_path, map_location=device)
    state_dict = bundle.get("state_dict", bundle)
    model = LSTMAutoencoder(feature_dim=feature_dim).to(device)
    model.load_state_dict(state_dict)
    model.eval()
    q01 = bundle.get("q01", 0.0)
    q99 = bundle.get("q99", 1.0)
    return model, q01, q99


class AnomalyDetector:
    def __init__(
        self,
        model_path: str = "./",
        initial_samples = {"sensor": 1000, "touch_drag": 1000, "touch_pressure": 1000},
        contamination=0.03, retrain_interval=50000000, n_estimators=200,
        anomaly_percentile=0.005, margin=0.002
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

        self.iforest_models = {m: None for m in self.modalities}
        self.iforest_scalers = {m: StandardScaler() for m in self.modalities}
        self.iforest_thresholds = {m: None for m in self.modalities}
        self.iforest_modes = {m: "collecting" for m in self.modalities}
        self.recent_data = {m: deque(maxlen=self.retrain_interval + self.initial_samples[m]) for m in self.modalities}

        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.lstm_models = {m: None for m in self.modalities}
        self.lstm_scalers = {m: None for m in self.modalities}
        self.lstm_thresholds = {m: None for m in self.modalities}
        self.lstm_features = {m: [] for m in self.modalities}
        self.lstm_seq_lens = {m: 20 for m in self.modalities}
        self.lstm_modes = {m: "collecting" for m in self.modalities}
        self.lstm_logs = {m: deque(maxlen=self.retrain_interval + self.initial_samples[m]) for m in self.modalities}
        self.lstm_retrain_interval = retrain_interval
        self.lstm_since_retrain = {m: 0 for m in self.modalities}

        for m in self.modalities:
            self._initialize_iforest_modality(m)
            self._initialize_lstm_modality(m)


    def _iforest_model_files(self, modality: str):
        mdir = self.model_path / "models" / modality / "iforest"
        mdir.mkdir(parents=True, exist_ok=True)
        return (mdir / "model.pkl", mdir / "scaler.pkl", mdir / "meta.json")

    def _lstm_model_files(self, modality: str):
        mdir = self.model_path / "models" / modality / "lstm"
        mdir.mkdir(parents=True, exist_ok=True)
        return (mdir / "model.pth", mdir / "scaler.pkl")


    def _initialize_iforest_modality(self, modality: str):
        model_file, scaler_file, meta_file = self._iforest_model_files(modality)
        if model_file.exists() and scaler_file.exists():
            try:
                with open(model_file, "rb") as f:
                    self.iforest_models[modality] = pickle.load(f)
                with open(scaler_file, "rb") as f:
                    self.iforest_scalers[modality] = pickle.load(f)
                if not hasattr(self.iforest_scalers[modality], "mean_"):
                    raise AttributeError()
                if meta_file.exists():
                    try:
                        meta = json.loads(meta_file.read_text(encoding="utf-8"))
                        self.iforest_thresholds[modality] = meta.get("threshold", None)
                    except Exception:
                        pass
                self.iforest_modes[modality] = "inference"
                logger.info(f"[{modality}-iForest] 모델 로드 완료")
            except Exception as e:
                logger.error(f"[{modality}-iForest] 로드 실패: {e}")
                self.iforest_modes[modality] = "collecting"
        else:
            logger.info(f"[{modality}-iForest] 모델 없음 -> {self.initial_samples[modality]}개 수집 후 학습")

    def _initialize_lstm_modality(self, modality: str):
        model_file, scaler_file = self._lstm_model_files(modality)
        if model_file.exists() and scaler_file.exists():
            try:
                scaler_bundle = joblib.load(scaler_file)
                self.lstm_scalers[modality] = scaler_bundle.get("scaler")
                self.lstm_features[modality] = scaler_bundle.get("features") or []
                self.lstm_seq_lens[modality] = scaler_bundle.get("seq_len", 20)
                if not self.lstm_features[modality]:
                    raise ValueError("LSTM 피처 정보 없음")

                lstm_model, q01, q99 = load_lstm_bundle(model_file, len(self.lstm_features[modality]), self.device)
                self.lstm_models[modality] = lstm_model
                self.lstm_thresholds[modality] = {"q01": float(q01), "q99": float(q99)}
                self.lstm_modes[modality] = "inference"
                logger.info(f"[{modality}-LSTM] 모델 로드 완료")
            except Exception as e:
                logger.error(f"[{modality}-LSTM] 로드 실패: {e}", exc_info=True)
                self.lstm_models[modality] = None
                self.lstm_scalers[modality] = None
                self.lstm_thresholds[modality] = None
                self.lstm_features[modality] = []
                self.lstm_modes[modality] = "collecting"
                logger.info(f"[{modality}-LSTM] fresh start → {self.initial_samples[modality]}개 수집 후 학습")
        else:
            self.lstm_modes[modality] = "collecting"
            logger.info(f"[{modality}-LSTM] 모델 없음 → {self.initial_samples[modality]}개 수집 후 학습")


    def _save_iforest_model_and_meta(self, modality: str, threshold: float):
        model_file, scaler_file, meta_file = self._iforest_model_files(modality)
        try:
            with open(model_file, "wb") as f:
                pickle.dump(self.iforest_models[modality], f)
            with open(scaler_file, "wb") as f:
                pickle.dump(self.iforest_scalers[modality], f)
            meta = {
                "modality": modality,
                "saved_at": datetime.now(timezone.utc).isoformat(),
                "threshold": float(threshold),
                "anomaly_percentile": self.anomaly_percentile,
                "margin": self.margin,
                "contamination": self.contamination,
                "n_estimators": self.n_estimators,
            }
            meta_file.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
            logger.info(f"[{modality}-iForest] 모델/스케일러/메타 저장 완료 → {model_file.parent}")
        except Exception as e:
            logger.error(f"[{modality}-iForest] 모델/메타 저장 실패: {e}")

    def _train_iforest_model(self, modality: str):
        data = np.array(self.recent_data[modality])
        if data.shape[0] < self.initial_samples[modality]:
            return False
        try:
            logger.info(f"[{modality}-iForest] 모델 학습 시작. 데이터 수: {data.shape[0]}")
            X_scaled = self.iforest_scalers[modality].fit_transform(data)
            model = IsolationForest(
                n_estimators=self.n_estimators,
                contamination=self.contamination,
                random_state=42,
                n_jobs=-1,
            )
            model.fit(X_scaled)
            self.iforest_models[modality] = model
            scores = model.decision_function(X_scaled)
            threshold = np.percentile(scores, self.anomaly_percentile * 100.0)
            self.iforest_thresholds[modality] = threshold
            self.iforest_modes[modality] = "inference"
            self._save_iforest_model_and_meta(modality, threshold)
            logger.info(f"[{modality}-iForest] 학습 완료 (threshold={threshold:.6f}) → inference")
            return True
        except Exception as e:
            logger.error(f"[{modality}-iForest] 학습/저장 중 오류: {e}")
            self.iforest_modes[modality] = "collecting"
            return False

    def observe_and_maybe_train(self, modality: str, features: np.ndarray):
        if modality not in self.modalities or features is None:
            return
        try:
            self.recent_data[modality].append(np.asarray(features, dtype=float))
            if self.iforest_modes[modality] == "collecting":
                cnt = len(self.recent_data[modality])
                th = self.initial_samples[modality]
                if (cnt % 25 == 0) or (cnt == th):
                    logger.info(f"[{modality}-iForest] collecting {cnt}/{th}")
                if cnt >= th:
                    self._train_iforest_model(modality)
        except Exception as e:
            logger.error(f"[{modality}-iForest] 관찰 중 오류: {e}")

    def _predict_one_iforest(self, modality: str, features: np.ndarray):
        if (
            self.iforest_modes[modality] != "inference"
            or self.iforest_models[modality] is None
            or not hasattr(self.iforest_scalers[modality], "mean_")
        ):
            return {"is_anomaly": False, "score": 0.0}
        try:
            Xs = self.iforest_scalers[modality].transform(features.reshape(1, -1))
            score = float(self.iforest_models[modality].decision_function(Xs)[0])
            thr = self.iforest_thresholds.get(modality)
            if thr is None:
                pred = int(self.iforest_models[modality].predict(Xs)[0])
                is_anom = (pred == -1)
            else:
                is_anom = (score <= (thr - self.margin))
            return {"is_anomaly": is_anom, "score": score}
        except Exception as e:
            logger.error(f"[{modality}-iForest] 추론 중 오류: {e}")
            return {"is_anomaly": False, "score": 0.0}

    def observe_and_maybe_train_lstm(self, modality: str, one_log: Dict[str, Any]):
        if modality not in self.modalities:
            return
        try:
            self.lstm_logs[modality].append(one_log)
            cnt = len(self.lstm_logs[modality])
            th = self.initial_samples[modality]

            if self.lstm_modes[modality] == "collecting":
                if (cnt % 25 == 0) or (cnt == th):
                    logger.info(f"[{modality}-LSTM] collecting {cnt}/{th}")
                if cnt >= th:
                    ok = self._train_lstm_model_from_logs(modality)
                    self.lstm_since_retrain[modality] = 0
                return


            self.lstm_since_retrain[modality] += 1
            if self.lstm_since_retrain[modality] >= self.lstm_retrain_interval:
                logger.info(f"[{modality}-LSTM] periodic retrain on {len(self.lstm_logs[modality])} logs")
                ok = self._train_lstm_model_from_logs(modality)

                self.lstm_since_retrain[modality] = 0 if ok else max(0, self.lstm_since_retrain[modality] // 2)

        except Exception as e:
            logger.error(f"[{modality}-LSTM] 관찰 중 오류: {e}", exc_info=True)

    def _train_lstm_model_from_logs(self, modality: str):
        try:
            logs = list(self.lstm_logs[modality])
            if not logs or len(logs) < self.initial_samples[modality]:
                logger.warning(f"[{modality}-LSTM] 학습 스킵: 데이터 부족({len(logs)}/{self.initial_samples[modality]})")
                return False

            df = parse_sensor_sequence_for_lstm(logs) if modality == "sensor" else parse_touch(logs)
            if df.empty:
                logger.warning(f"[{modality}-LSTM] 학습 스킵: 파싱 empty")
                return False

            numeric_cols = [c for c in df.columns if pd.api.types.is_numeric_dtype(df[c])]
            if not numeric_cols:
                logger.warning(f"[{modality}-LSTM] 학습 스킵: 숫자 피처 없음")
                return False

            scaler = StandardScaler()
            X = scaler.fit_transform(df[numeric_cols].fillna(0.0).values)

            seq_len = self.lstm_seq_lens[modality]
            seqs = create_sequences(X, seq_len)
            if seqs.size == 0:
                logger.warning(f"[{modality}-LSTM] 학습 스킵: 시퀀스 생성 실패")
                return False

            X_tensor = torch.tensor(seqs, dtype=torch.float32).to(self.device)
            model = LSTMAutoencoder(feature_dim=X_tensor.shape[2]).to(self.device)
            criterion = nn.MSELoss()
            optimizer = optim.Adam(model.parameters(), lr=1e-3)

            epochs = 10
            for ep in range(epochs):
                model.train()
                optimizer.zero_grad()
                recon = model(X_tensor)
                loss = criterion(recon, X_tensor)
                loss.backward()
                optimizer.step()
                if ep in (0, epochs//2, epochs-1):
                    logger.info(f"[{modality}-LSTM] epoch {ep+1}/{epochs} loss={loss.item():.6f}")

            model.eval()
            with torch.no_grad():
                recon = model(X_tensor)
                errors = torch.mean((X_tensor - recon) ** 2, dim=(1, 2)).cpu().numpy()
            q01, q99 = np.percentile(errors, [1, 99])

            self.lstm_models[modality] = model
            self.lstm_scalers[modality] = scaler
            self.lstm_thresholds[modality] = {"q01": float(q01), "q99": float(q99)}
            self.lstm_features[modality] = list(numeric_cols)
            self.lstm_modes[modality] = "inference"

            model_file, scaler_file = self._lstm_model_files(modality)
            try:
                torch.save({"state_dict": model.state_dict(), "q01": float(q01), "q99": float(q99)}, model_file)
                joblib.dump({"scaler": scaler, "features": self.lstm_features[modality],
                             "seq_len": seq_len}, scaler_file)
                logger.info(f"[{modality}-LSTM] 모델/스케일러 저장 완료 → {model_file.parent}")
            except Exception as se:
                logger.warning(f"[{modality}-LSTM] 저장 경고: {se}")

            logger.info(f"[{modality}-LSTM] 학습 완료 → inference (features={len(numeric_cols)}, seq_len={seq_len})")
            return True
        except Exception as e:
            logger.error(f"[{modality}-LSTM] 학습 실패: {e}", exc_info=True)
            self.lstm_modes[modality] = "collecting"
            return False

    def _predict_lstm(self, modality: str, logs: List[Dict[str, Any]]):
        if (
            self.lstm_modes[modality] != "inference"
            or self.lstm_models[modality] is None
            or self.lstm_scalers[modality] is None
            or not self.lstm_features[modality]
        ):
            return None
        try:
            acc_logs = list(self.lstm_logs[modality])
            if not acc_logs:
                return None

            df = parse_sensor_sequence_for_lstm(acc_logs) if modality == "sensor" else parse_touch(acc_logs)
            if df.empty:
                return None

            feature_list = list(self.lstm_features[modality])
            valid_df = df.reindex(columns=feature_list).fillna(0.0)
            if len(valid_df) < self.lstm_seq_lens[modality]:
                return None

            features_np = self.lstm_scalers[modality].transform(valid_df.values)
            seqs = create_sequences(features_np, self.lstm_seq_lens[modality])
            if seqs.size == 0:
                return None

            X = torch.tensor(seqs, dtype=torch.float32).to(self.device)
            with torch.no_grad():
                recon = self.lstm_models[modality](X)
                errors = torch.mean((X - recon) ** 2, dim=(1, 2)).cpu().numpy()

            thr = self.lstm_thresholds[modality]
            lower_bound = thr["q01"] * 0.5
            upper_bound = thr["q99"] * 1.5

            return {
                i: {"is_anomaly": bool((float(e) <= lower_bound) or (float(e) >= upper_bound)),
                    "score": float(e)}
                for i, e in enumerate(errors)
            }
        except Exception as e:
            logger.error(f"[{modality}-LSTM] 추론 중 오류: {e}", exc_info=True)
            return None

    def _extract_sensor_features_from_dict(self, params: Dict[str, Any]):
        if not params:
            return np.zeros(3, dtype=float)
        x = params.get("x", 0.0); y = params.get("y", 0.0); z = params.get("z", 0.0)
        return np.array([x, y, z], dtype=float)

    def _extract_touch_drag_features_from_dict(self, params: Dict[str, Any]):
        if not params:
            return np.zeros(9, dtype=float)
        duration = params.get("duration", 0.0)
        total_distance = params.get("total_distance", 0.0)
        straightness = params.get("straightness", 0.0)
        move_count = params.get("move_count", 0.0)
        velocity = (total_distance / (duration / 1000.0)) if duration > 0 else 0.0
        dirs = str(params.get("drag_direction", "")).lower()
        dir_down = 1.0 if dirs == "down" else 0.0
        dir_up = 1.0 if dirs == "up" else 0.0
        dir_left = 1.0 if dirs == "left" else 0.0
        dir_right = 1.0 if dirs == "right" else 0.0
        return np.array(
            [duration, total_distance, velocity, straightness, move_count, dir_down, dir_up, dir_left, dir_right],
            dtype=float,
        )

    def _extract_touch_pressure_features_from_dict(self, params: Dict[str, Any]):
        if not params:
            return np.zeros(4, dtype=float)
        duration = params.get("touch_duration", 0.0)
        size = params.get("size", 0.0)
        x = params.get("x", 0.0); y = params.get("y", 0.0)
        return np.array([duration, size, x, y], dtype=float)

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
    location: Optional[Dict[str, Any]] = None

def _infer_modality(action_type: str) -> str:
    at = (action_type or "").lower()
    if at.startswith("sensor_"):
        return "sensor"
    if "drag" in at:
        return "touch_drag"
    if "touch_pressure" in at:
        return "touch_pressure"
    if at.startswith("touch_"):
        return "touch_pressure"
    # ▼ 추가: 네트워크
    if at.startswith("network_") or at == "network":
        return "network"
    return "unknown"


def _extract_features_by_modality(modality: str, params: Dict[str, Any]):
    if modality == "sensor":
        return detector._extract_sensor_features_from_dict(params)
    elif modality == "touch_drag":
        return detector._extract_touch_drag_features_from_dict(params)
    elif modality == "touch_pressure":
        return detector._extract_touch_pressure_features_from_dict(params)
    else:
        return None

def _get_lstm_map_by_seq(modality: str, logs: List[Dict[str, Any]]) -> Dict[int, Dict[str, float]]:
    lstm_map: Dict[int, Dict[str, float]] = {}
    out = detector._predict_lstm(modality, logs)
    if not out:
        return lstm_map
    values = list(out.values())
    if not values:
        return lstm_map
    start = max(0, len(logs) - len(values))
    for i, val in enumerate(values):
        try:
            seq = int(logs[start + i].get("sequence_index", start + i))
        except Exception:
            seq = start + i
        lstm_map[seq] = {
            "is_anomaly": bool(val.get("is_anomaly", False)),
            "score": float(val.get("score", 0.0)),
        }
    return lstm_map

@app.post("/predict")
def predict_anomaly(payloads: List[PredictPayload]):
    results: List[Dict[str, Any]] = []
    for p in payloads:
        modality = _infer_modality(p.action_type)
        if modality == "unknown":
            results.append({
                "sequence_index": p.sequence_index, "modality": "unknown",
                "timestamp": p.timestamp, "is_anomaly": False, "anomaly_score": 0.0,
            })
            continue

        feats = _extract_features_by_modality(modality, p.params)
        if feats is None:
            results.append({
                "sequence_index": p.sequence_index, "modality": modality,
                "timestamp": p.timestamp, "is_anomaly": False, "anomaly_score": 0.0,
            })
            continue

        detector.observe_and_maybe_train(modality, feats)
        detector.observe_and_maybe_train_lstm(modality, p.dict())

        try:
            res = detector._predict_one_iforest(modality, feats)
            is_anom = bool(res.get("is_anomaly", False))
            score = float(res.get("score", 0.0))
        except Exception:
            is_anom, score = False, 0.0

        results.append({
            "sequence_index": p.sequence_index, "modality": modality,
            "timestamp": p.timestamp, "is_anomaly": is_anom, "anomaly_score": score,
        })
    return results

@app.post("/predict_hybrid")
def predict_hybrid(payloads: List[PredictPayload]):
    logs: List[Dict[str, Any]] = [p.model_dump() for p in payloads]

    grouped: Dict[str, List[Dict[str, Any]]] = {"sensor": [], "touch_drag": [], "touch_pressure": []}
    for log in logs:
        m = _infer_modality(log.get("action_type", ""))
        if m in grouped:
            grouped[m].append(log)

    final_results: List[Dict[str, Any]] = []
    for modality, mlogs in grouped.items():
        if not mlogs:
            continue

        for log in mlogs:
            detector.observe_and_maybe_train_lstm(modality, log)

        iforest_list = []
        for log in mlogs:
            feats = _extract_features_by_modality(modality, log.get("params", {}))
            if feats is not None:
                detector.observe_and_maybe_train(modality, feats)
            if feats is None:
                iforest_list.append({"is_anomaly": False, "score": 0.0})
                continue
            try:
                res = detector._predict_one_iforest(modality, feats)
                iforest_list.append({
                    "is_anomaly": bool(res.get("is_anomaly", False)),
                    "score": float(res.get("score", 0.0)),
                })
            except Exception:
                iforest_list.append({"is_anomaly": False, "score": 0.0})

        lstm_by_seq = _get_lstm_map_by_seq(modality, mlogs)

        for i, log in enumerate(mlogs):
            seq = int(log.get("sequence_index", i))
            ts = log.get("timestamp")
            iforest_res = iforest_list[i] if i < len(iforest_list) else {"is_anomaly": False, "score": 0.0}
            lstm_res = lstm_by_seq.get(seq, {"is_anomaly": False, "score": 0.0})

            is_iforest = bool(iforest_res.get("is_anomaly", False))
            s_iforest = float(iforest_res.get("score", 0.0))
            is_lstm = bool(lstm_res.get("is_anomaly", False))
            s_lstm = float(lstm_res.get("score", 0.0))

            combined_score = (s_iforest + s_lstm) / 2.0 if (s_iforest or s_lstm) else 0.0
            is_combined = is_iforest or is_lstm

            final_results.append({
                "sequence_index": seq,
                "modality": modality,
                "timestamp": ts,
                "is_anomaly_iforest": is_iforest,
                "anomaly_score_iforest": s_iforest,
                "is_anomaly_lstm": is_lstm,
                "anomaly_score_lstm": s_lstm,
                "is_anomaly_combined": is_combined,
                "anomaly_score_combined": combined_score,
            })

    return final_results
