# train_lstm_touch_pressure.py
import argparse, sys, os
import numpy as np
import pandas as pd
import torch, torch.nn as nn, joblib
from sklearn.preprocessing import MinMaxScaler
from utils import load_json_logs, parse_touch, create_sequences

class LSTMAutoencoder(nn.Module):
    def __init__(self, n_features, hidden=64, latent=32, num_layers=1):
        super().__init__()
        self.enc = nn.LSTM(n_features, hidden, num_layers=num_layers, batch_first=True)
        self.to_z = nn.Linear(hidden, latent)
        self.from_z = nn.Linear(latent, hidden)
        self.dec = nn.LSTM(n_features, hidden, num_layers=num_layers, batch_first=True)
        self.out_proj = nn.Linear(hidden, n_features)

    def forward(self, x):
        h, _ = self.enc(x)
        z = self.to_z(h[:, -1, :])
        h0 = self.from_z(z).unsqueeze(0)
        c0 = torch.zeros_like(h0)
        dec_in = torch.zeros_like(x)
        y, _ = self.dec(dec_in, (h0, c0))
        return self.out_proj(y)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--touch_path", type=str, default=".")
    ap.add_argument("--seq_len", type=int, default=20)
    ap.add_argument("--epochs", type=int, default=10)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--device", type=str, default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--out_model", type=str, default="lstm_ae_touch_pressure.pth")
    ap.add_argument("--out_scaler", type=str, default="scaler_touch_pressure.pkl")
    args = ap.parse_args()

    out_dir = os.path.dirname(args.out_model) or "."
    os.makedirs(out_dir, exist_ok=True)

    logs = load_json_logs(args.touch_path, filename_contains="touch")
    df = parse_touch(logs)
    if df is None or df.empty:
        print("⚠️ Touch 데이터 없음 → 학습 스킵"); sys.exit(100)

    ev = df["touch_event"].astype(str).str.lower()
    df = df[ev.str.contains("pressure", na=False)]
    if df.empty:
        print("⚠️ pressure 이벤트 없음 → 학습 스킵"); sys.exit(100)

    df = df.sort_values("ts")

    feature_pool = ["touch_x", "touch_y", "touch_pressure", "touch_size"]
    feature_cols = [c for c in feature_pool if c in df.columns]

    valid_cols = []
    for c in feature_cols:
        col = df[c]
        if col.notna().sum() == 0 or col.nunique(dropna=True) <= 1:
            continue
        valid_cols.append(c)

    if not valid_cols:
        print("⚠️ 사용 가능한 피처 없음 → 스킵"); sys.exit(100)

    # [수정] 학습 데이터 필터링 로직 추가
    initial_rows = len(df)
    
    # 조건 1: 피처 값 중 하나라도 0인 로그 제외
    df = df[(df[valid_cols] != 0).all(axis=1)]
    rows_after_zero_filter = len(df)
    
    # 조건 2: 이전 로그와 모든 피처 값이 동일한 중복 로그 제외
    df = df.drop_duplicates(subset=valid_cols, keep='first')
    final_rows = len(df)

    skipped_for_zero = initial_rows - rows_after_zero_filter
    skipped_for_duplicates = rows_after_zero_filter - final_rows

    if final_rows == 0:
        print("⚠️ 필터링 후 남은 데이터 없음 → 학습 스킵"); sys.exit(100)

    df[valid_cols] = df[valid_cols].fillna(0.0)

    scaler = MinMaxScaler()
    Xnum = scaler.fit_transform(df[valid_cols].to_numpy(dtype=np.float32))

    seqs = create_sequences(Xnum, args.seq_len)
    if seqs is None or len(seqs) == 0:
        print("⚠️ 시퀀스 없음 → 스킵"); sys.exit(100)

    device = args.device
    X = torch.tensor(seqs, dtype=torch.float32).to(device)
    model = LSTMAutoencoder(n_features=len(valid_cols)).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=args.lr)
    loss_fn = nn.MSELoss()

    model.train()
    for ep in range(1, args.epochs + 1):
        opt.zero_grad(set_to_none=True)
        out = model(X)
        loss = loss_fn(out, X)
        loss.backward()
        opt.step()
        if ep % max(1, args.epochs // 5) == 0:
            print(f"[pressure] {ep}/{args.epochs} loss={loss.item():.6f}")

    model.eval()
    with torch.no_grad():
        recon = model(X)
        mse = torch.mean((X - recon) ** 2, dim=(1, 2)).cpu().numpy()
    q01 = float(np.quantile(mse, 0.01))
    q99 = float(np.quantile(mse, 0.99))
    print(f"[pressure] train quantiles → q01={q01:.6f}, q99={q99:.6f}")

    torch.save({"state_dict": model.state_dict(), "q01": q01, "q99": q99}, args.out_model)
    joblib.dump({"scaler": scaler, "features": valid_cols, "seq_len": args.seq_len}, args.out_scaler)
    print(f"✅ 저장: {args.out_model} / {args.out_scaler}")

    # [수정] 필터링된 로그 수 통계 출력
    print("\n--- 로그 필터링 통계 ---")
    print(f"초기 로그 수: {initial_rows}개")
    print(f"  - 0값 포함으로 제외: {skipped_for_zero}개")
    print(f"  - 중복 값으로 제외: {skipped_for_duplicates}개")
    print(f"최종 학습 사용 로그 수: {final_rows}개")
    
    sys.exit(0)

if __name__ == "__main__":
    main()