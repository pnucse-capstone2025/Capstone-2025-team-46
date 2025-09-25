import os
import json
import argparse
import sys
from glob import glob
from typing import List, Dict, Any, Optional
import numpy as np
import pandas as pd
import joblib
import seaborn as sns

# matplotlib이 GUI 환경 없이 이미지를 파일로 저장할 수 있도록 백엔드를 설정합니다.
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import torch
import torch.nn as nn
# F1, Precision, Recall 스코어를 명시적으로 추가
from sklearn.metrics import (classification_report, confusion_matrix, accuracy_score, 
                             precision_score, recall_score, f1_score)

# ==================== 모델 정의 ====================
# 기본 평가 스크립트용 모델
class LSTMAutoencoder(nn.Module):
    """LSTM 기반 오토인코더 모델"""
    def __init__(self, feature_dim: int, hidden_dim: int = 64, latent_dim: int = 32):
        super().__init__()
        self.enc_lstm = nn.LSTM(feature_dim, hidden_dim, batch_first=True)
        self.enc_fc = nn.Linear(hidden_dim, latent_dim)
        self.dec_fc = nn.Linear(latent_dim, hidden_dim)
        self.dec_lstm = nn.LSTM(hidden_dim, feature_dim, batch_first=True)

    def forward(self, x):
        enc_out, _ = self.enc_lstm(x)
        z = self.enc_fc(enc_out[:, -1, :])
        dec_in = self.dec_fc(z).unsqueeze(1).repeat(1, x.size(1), 1)
        dec_out, _ = self.dec_lstm(dec_in)
        return dec_out

# detect_sensor.py 와 호환되는 모델 구조
class LSTMAutoencoderDetect(nn.Module):
    def __init__(self, feature_dim, hidden_dim=64, latent_dim=32, num_layers=1):
        super().__init__()
        self.enc = nn.LSTM(feature_dim, hidden_dim, num_layers=num_layers, batch_first=True)
        self.to_z = nn.Linear(hidden_dim, latent_dim)
        self.from_z = nn.Linear(latent_dim, hidden_dim)
        self.dec = nn.LSTM(feature_dim, hidden_dim, num_layers=num_layers, batch_first=True)
        self.out_proj = nn.Linear(hidden_dim, feature_dim)

    def forward(self, x):
        h, _ = self.enc(x); z = self.to_z(h[:, -1, :]); h0 = self.from_z(z).unsqueeze(0)
        c0 = torch.zeros_like(h0); dec_in = torch.zeros_like(x); y, _ = self.dec(dec_in, (h0, c0))
        return self.out_proj(y)

# ==================== 유틸리티 함수 ====================
def create_sequences_with_pad(values: np.ndarray, seq_len: int) -> np.ndarray:
    """데이터를 시퀀스로 변환합니다. 데이터가 시퀀스 길이보다 짧으면 패딩합니다."""
    n = len(values)
    if n == 0:
        return np.empty((0, seq_len, values.shape[1]), dtype=values.dtype)
    if n >= seq_len:
        return np.stack([values[i:i + seq_len] for i in range(n - seq_len + 1)], axis=0)
    pad_len = seq_len - n
    last = values[-1:].repeat(pad_len, axis=0)
    seq = np.concatenate([values, last], axis=0)[None, ...]
    return seq

def _flatten_and_filter_logs(logs: List[Dict[str, Any]], mode: str) -> pd.DataFrame:
    """JSON 로그를 평탄화하고 모드에 따라 필터링합니다."""
    rows = []
    if not all(isinstance(item, dict) for item in logs):
        return pd.DataFrame()

    for item in logs:
        params = item.get("params", item)
        row = {
            "action_type": item.get("action_type", "unknown"),
            "type": item.get("type", "unknown"),
            "x": params.get("x", np.nan), "y": params.get("y", np.nan),
            "pressure": params.get("pressure", np.nan), "size": params.get("size", np.nan),
            "ts": params.get("timestamp", item.get("ts", np.nan)),
            "seq": item.get("seq", params.get("sequence_index", np.nan)),
            "start_x": params.get("start_x"), "end_x": params.get("end_x"),
            "start_y": params.get("start_y"), "end_y": params.get("end_y"),
            "total_distance": params.get("total_distance"), "duration": params.get("duration"),
        }
        rows.append(row)
    
    df = pd.DataFrame(rows)
    if df.empty: return df

    if mode == 'touch_drag':
        df = df[df['action_type'].str.contains('drag|move|swipe', case=False, na=False)].copy()
    elif mode == 'touch_pressure':
        df = df[df['action_type'].str.contains('pressure', case=False, na=False)].copy()
    elif mode == 'sensor' and 'type' in df.columns:
        df = pd.get_dummies(df, columns=['type'], prefix='', prefix_sep='')
    return df

def _read_one_json(path: str, mode: str) -> pd.DataFrame:
    """단일 JSON 파일을 읽어 DataFrame으로 변환합니다."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        logs = data.get("logs", data) if isinstance(data, dict) else data if isinstance(data, list) else []
        return _flatten_and_filter_logs(logs, mode)
    except (json.JSONDecodeError, FileNotFoundError):
        return pd.DataFrame()

def load_folder_as_df(folder: str, mode: str) -> pd.DataFrame:
    """폴더 내의 모든 JSON 파일을 읽어 하나의 DataFrame으로 합칩니다."""
    if not folder or not os.path.isdir(folder): return pd.DataFrame()
    files = glob(os.path.join(folder, "**", "*.json"), recursive=True)
    if not files:
        print(f"[Warning] No '.json' files found in '{folder}'")
        return pd.DataFrame()
    dfs = [_read_one_json(p, mode) for p in files]
    non_empty_dfs = [df for df in dfs if not df.empty]
    return pd.concat(non_empty_dfs, ignore_index=True) if non_empty_dfs else pd.DataFrame()

# ==================== 전처리 ====================
def align_and_scale(df: pd.DataFrame, scaler_path: str) -> pd.DataFrame:
    """학습 시 사용된 스케일러로 데이터를 변환하고, 컬럼을 정렬합니다."""
    scaler_bundle = joblib.load(scaler_path)
    scaler = scaler_bundle.get('scaler') if isinstance(scaler_bundle, dict) else scaler_bundle

    if not hasattr(scaler, 'transform'):
        raise TypeError(f"Object from {scaler_path} is not a valid scaler.")
    
    expected_cols = scaler_bundle.get('features') if isinstance(scaler_bundle, dict) else getattr(scaler, "feature_names_in_", None)
    
    if expected_cols is None:
        raise ValueError("Scaler is missing feature names. Please re-generate the scaler with feature names included.")

    aligned_df = pd.DataFrame()
    for col in expected_cols:
        if col in df.columns:
            aligned_df[col] = df[col]
        else:
            aligned_df[col] = 0
    
    X_scaled = scaler.transform(aligned_df.fillna(0))
    return pd.DataFrame(X_scaled, columns=expected_cols, dtype=np.float32)

def _infer_input_dim_from_state_dict(sd: dict) -> int:
    """모델 state_dict에서 입력 차원(feature_dim)을 추론합니다."""
    for key in ["enc_lstm.weight_ih_l0", "enc.weight_ih_l0"]:
        if key in sd:
            return int(sd[key].shape[1])
    raise RuntimeError(f"Could not infer feature dimension from checkpoint keys: {list(sd.keys())}")

# ==================== 시각화 및 최적화 함수 ====================
def plot_confusion_matrix_image(cm, class_names, mode, save_path):
    """Confusion Matrix를 이미지 파일로 저장합니다."""
    plt.figure(figsize=(8, 6))
    sns.heatmap(cm, annot=True, fmt='d', cmap='Blues', 
                xticklabels=class_names, yticklabels=class_names)
    plt.title(f'{mode.replace("_", " ").title()} Confusion Matrix')
    plt.ylabel('True Label'); plt.xlabel('Predicted Label')
    plt.tight_layout(); plt.savefig(save_path, dpi=300); plt.close()
    print(f"[INFO] Confusion matrix plot saved to '{save_path}'")

# ==================== 평가 ====================
def evaluate(mode: str, normal_dir: str, abnormal_dir: str, model_path: str,
             scaler_path: str, seq_len: int = 50, output_dir: str = "evaluation_graphs") -> Optional[Dict]:

    print(f"[INFO] Loading data (mode={mode})")
    df_normal_raw = load_folder_as_df(normal_dir, mode)
    df_abnorm_raw = load_folder_as_df(abnormal_dir, mode)

    if df_normal_raw.empty: 
        print(f"[ERROR] Normal data not found or is empty for mode '{mode}' in '{normal_dir}'. Skipping.")
        return None
    if df_abnorm_raw.empty: 
        print(f"[ERROR] Abnormal data not found or is empty for mode '{mode}' in '{abnormal_dir}'. Skipping.")
        return None

    Xn_df = align_and_scale(df_normal_raw, scaler_path)
    Xa_df = align_and_scale(df_abnorm_raw, scaler_path)

    device = "cuda" if torch.cuda.is_available() else "cpu"
    bundle = torch.load(model_path, map_location=device)
    sd = bundle.get("state_dict", bundle) if isinstance(bundle, dict) else bundle
    
    feature_dim = _infer_input_dim_from_state_dict(sd)
    
    if "out_proj.weight" in sd:
        model = LSTMAutoencoderDetect(feature_dim=feature_dim).to(device)
        print("[INFO] Loaded 'Detect' model architecture.")
    else:
        model = LSTMAutoencoder(feature_dim=feature_dim).to(device)
        print("[INFO] Loaded 'Standard' model architecture.")

    model.load_state_dict(sd); model.eval()
    
    seq_n = create_sequences_with_pad(Xn_df.values, seq_len)
    seq_a = create_sequences_with_pad(Xa_df.values, seq_len)

    def get_reconstruction_errors(seqs: np.ndarray) -> np.ndarray:
        errors = []
        with torch.no_grad():
            for s_np in seqs:
                s = torch.from_numpy(s_np).to(torch.float32).unsqueeze(0).to(device)
                reconstructed = model(s)
                loss = torch.mean((s - reconstructed) ** 2).item()
                errors.append(loss)
        return np.array(errors, dtype=np.float32)

    err_n = get_reconstruction_errors(seq_n)
    err_a = get_reconstruction_errors(seq_a)

    y_true = np.concatenate([np.zeros(len(err_n)), np.ones(len(err_a))])
    all_errors = np.concatenate([err_n, err_a])
    
    # --- 수정된 부분: 임계값을 99 백분위수로 설정 ---
    optimal_threshold = np.percentile(err_n, 99)
    print(f"[INFO] New threshold based on 99th percentile of normal data: {optimal_threshold:.6f}")
    
    y_pred = (all_errors > optimal_threshold).astype(int)
    
    print("\n" + "="*35 + f"\n    EVALUATION REPORT: {mode.upper()}\n" + "="*35)
    print(classification_report(y_true, y_pred, target_names=["Normal", "Abnormal"], digits=4))
    
    results = {
        "mode": mode,
        "metrics": {
            "Accuracy": accuracy_score(y_true, y_pred),
            "Precision": precision_score(y_true, y_pred, zero_division=0),
            "Recall": recall_score(y_true, y_pred, zero_division=0),
            "F1-Score": f1_score(y_true, y_pred, zero_division=0)
        }
    }
    
    cm = confusion_matrix(y_true, y_pred)
    plot_confusion_matrix_image(cm, ["Normal", "Abnormal"], mode, os.path.join(output_dir, f"cm_{mode}.png"))
    
    plt.figure(figsize=(10, 6))
    bins = min(50, len(np.unique(all_errors)))
    if bins < 2: bins = 2
    
    plt.hist(err_n, bins=bins, alpha=0.7, label="Normal Errors", color='blue')
    plt.hist(err_a, bins=bins, alpha=0.7, label="Abnormal Errors", color='red')
    plt.axvline(optimal_threshold, color='red', linestyle='--', linewidth=2, label=f"Threshold (99th Perc.) = {optimal_threshold:.4f}")
    plt.title(f'{mode.replace("_", " ").title()} Error Distribution')
    plt.xlabel("Reconstruction Error (MSE)"); plt.ylabel("Number of Sequences")
    plt.legend(); plt.grid(True, which='both', linestyle='--', linewidth=0.5)
    plt.tight_layout(); plt.savefig(os.path.join(output_dir, f"dist_{mode}.png"), dpi=300); plt.close()
    print(f"[INFO] Error distribution plot saved to '{os.path.join(output_dir, f'dist_{mode}.png')}'")
    
    return results

# ==================== 메인 실행 블록 ====================
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Evaluate multiple anomaly detection models.")
    parser.add_argument("--seq_len", type=int, default=50, help="Sequence length.")
    args = parser.parse_args()

    modes_to_run = ["sensor", "touch_drag", "touch_pressure"]
    base_log_dir = r"C:\Users\xogus\Desktop\Kgl_Model\logs\eval_logs"
    output_dir = "evaluation_graphs"
    os.makedirs(output_dir, exist_ok=True)
    
    all_results = {}

    for mode in modes_to_run:
        print("\n" + "#"*50 + f"\n  STARTING EVALUATION FOR MODE: {mode.upper()}\n" + "#"*50)
        
        log_type = "touch" if "touch" in mode else "sensor"
        normal_dir = os.path.join(base_log_dir, f'normal_{log_type}')
        abnormal_dir = os.path.join(base_log_dir, f'abnormal_{log_type}')
        model_path = f"lstm_ae_{mode}.pth"
        scaler_path = f"scaler_{mode}.pkl"

        try:
            result = evaluate(
                mode=mode, normal_dir=normal_dir, abnormal_dir=abnormal_dir,
                model_path=model_path, scaler_path=scaler_path,
                seq_len=args.seq_len, output_dir=output_dir
            )
            if result: all_results[mode] = result
        except Exception as e:
            print(f"\n[ERROR] Evaluation failed for mode '{mode}': {e}")
    
    if all_results:
        print("\n\n" + "#"*60 + "\n" + " " * 18 + "OVERALL PERFORMANCE SUMMARY\n" + "#"*60)
        header = f"{'Model (Mode)':<20} | {'F1-Score':>10} | {'Accuracy':>10} | {'Precision':>10} | {'Recall':>10}"
        print(header); print("-" * len(header))
        for mode, data in all_results.items():
            m = data['metrics']
            row = f"{mode.replace('_', ' ').title():<20} | {m['F1-Score']:>10.4f} | {m['Accuracy']:>10.4f} | {m['Precision']:>10.4f} | {m['Recall']:>10.4f}"
            print(row)
        print("#"*60)
        print(f"\n[SUCCESS] All plots have been saved to the '{output_dir}' directory.")
    else:
        print("\n[INFO] No evaluation results were generated.")