import os
import json
import torch
import torch.nn as nn
import pandas as pd
import numpy as np
import joblib
from utils import load_json_logs, parse_touch, create_sequences
import warnings

# [수정] 모든 UserWarning을 무시하도록 설정
warnings.filterwarnings("ignore", category=UserWarning)

# ====== 설정 ======
ROOT_DIR = r"C:\Users\xogus\Desktop\Kgl_Model"
LOG_DIR = os.path.join(ROOT_DIR, "logs", "normal", "touch")
MODEL_PATH = os.path.join(ROOT_DIR, "lstm_ae_touch_pressure.pth")
SCALER_PATH = os.path.join(ROOT_DIR, "scaler_touch_pressure.pkl")
OUTPUT_JSON_PATH = "pressure_anomalies.json"

SEQ_LEN = 20
THRESHOLD_MULTIPLIER = {"lower": 0.5, "upper": 1.5}
#THRESHOLD_MULTIPLIER = {"lower": 1, "upper": 1}
# ====== 모델 정의 ======
class LSTMAutoencoder(nn.Module):
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

def load_model_bundle(model_path, feature_dim, device):
    bundle = torch.load(model_path, map_location=device)
    state_dict = bundle.get('state_dict', bundle); model = LSTMAutoencoder(feature_dim=feature_dim).to(device)
    model.load_state_dict(state_dict); model.eval()
    q01 = bundle.get('q01', 0.0); q99 = bundle.get('q99', 1.0)
    return model, q01, q99

def process_file(file_path, model, scaler, num_cols, q01, q99, device):
    try:
        logs = []; content = open(file_path, 'r', encoding='utf-8').read()
        if content.strip(): logs = json.loads(content)
        if not logs: return []
        df = parse_touch(logs)
    except Exception:
        return []

    df = df[df["touch_event"].str.contains("pressure", case=False, na=False)].copy()
    if df.empty: return []

    df.sort_values("ts", inplace=True); df.reset_index(drop=True, inplace=True)
    
    for col in num_cols:
        if col not in df.columns: df[col] = 0.0
    
    df_filtered = df.drop_duplicates(subset=num_cols, keep='first')
    if df_filtered.empty: return []

    # [수정] .values를 사용하여 sklearn 경고 메시지 방지
    features_np = scaler.transform(df_filtered[num_cols].values)
    features = pd.DataFrame(features_np, columns=num_cols, index=df_filtered.index)
    features = features.fillna(0).astype(np.float32)
    
    seqs = create_sequences(features.values, SEQ_LEN) if len(features) >= SEQ_LEN else features.values.reshape(1, len(features), len(num_cols))
    if len(seqs) == 0: return []

    anomalies_found = []
    X = torch.tensor(seqs, dtype=torch.float32).to(device)
    with torch.no_grad():
        recon = model(X)
        errors = torch.mean((X - recon)**2, dim=(1, 2)).cpu().numpy()

    lower_bound = q01 * THRESHOLD_MULTIPLIER["lower"]
    upper_bound = q99 * THRESHOLD_MULTIPLIER["upper"]
    anomaly_indices = np.where((errors <= lower_bound) | (errors >= upper_bound))[0]

    for i in anomaly_indices:
        original_log_index = df_filtered.index[i]
        anomaly_log = df.loc[original_log_index]
        report = {
            "modality": "touch_pressure", "file": os.path.basename(file_path),
            "timestamp": pd.to_datetime(anomaly_log['ts'], unit='ms').isoformat(),
            "anomaly_score": float(errors[i]), "is_anomaly": True
        }
        anomalies_found.append(report)
    return anomalies_found

def main():
    device = "cuda" if torch.cuda.is_available() else "cpu"
    scaler_bundle = joblib.load(SCALER_PATH)
    scaler = scaler_bundle.get('scaler') if isinstance(scaler_bundle, dict) else scaler_bundle
    num_cols = scaler_bundle.get('features', list(getattr(scaler, "feature_names_in_", [])))
    if not num_cols: raise ValueError("스케일러에서 피처 이름을 가져올 수 없습니다.")
    
    feature_dim = len(num_cols)
    model, q01, q99 = load_model_bundle(MODEL_PATH, feature_dim, device)
    
    print("="*20 + " Touch Pressure Anomaly Detection " + "="*20)
    all_anomalies = []
    log_files = [f for f in os.listdir(LOG_DIR) if os.path.isfile(os.path.join(LOG_DIR, f))]
    
    for filename in log_files:
        file_path = os.path.join(LOG_DIR, filename)
        anomalies = process_file(file_path, model, scaler, num_cols, q01, q99, device)
        if anomalies:
            all_anomalies.extend(anomalies)
            print(f"🚨 Anomaly Detected in '{filename}':")
            for anom in anomalies:
                print(f"  - Score: {anom['anomaly_score']:.6f} at {anom['timestamp']}")
            
    if all_anomalies:
        with open(OUTPUT_JSON_PATH, 'w', encoding='utf-8') as f:
            json.dump(all_anomalies, f, indent=4, ensure_ascii=False)
        print(f"\n✅ Total {len(all_anomalies)} anomalies found. Results saved to '{OUTPUT_JSON_PATH}'")
    else:
        print(f"\n🎉 No anomalies were found in {os.path.basename(__file__)}.")
        
    return all_anomalies

if __name__ == "__main__":
    main()