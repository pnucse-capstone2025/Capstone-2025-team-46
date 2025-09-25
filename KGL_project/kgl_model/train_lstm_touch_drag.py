# train_lstm_touch_drag.py
import argparse, sys
import numpy as np
import pandas as pd
import torch, torch.nn as nn, joblib
from sklearn.preprocessing import MinMaxScaler
from utils import load_json_logs, parse_touch, create_sequences

class LSTMAE(nn.Module):
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
    ap.add_argument("--out_model", type=str, default="lstm_ae_touch_drag.pth")
    ap.add_argument("--out_scaler", type=str, default="scaler_touch_drag.pkl")
    args = ap.parse_args()

    try:
        logs = load_json_logs(args.touch_path, filename_contains="touch")
    except TypeError:
        logs = load_json_logs(args.touch_path)

    df = parse_touch(logs)
    if df is None or df.empty:
        print("⚠️ Touch 데이터 없음 → 학습 스킵"); sys.exit(100)

    ev = df["touch_event"].astype(str).str.lower()
    drag_df = df[ev.str.contains(r"(?:drag|move|swipe)", regex=True, na=False)]
    if drag_df.empty:
        print("⚠️ drag/move 이벤트 없음 → 전체 터치 로그 사용")
    else:
        df = drag_df

    df = df.sort_values("ts").copy()
    print("[drag] columns:", sorted(df.columns.tolist())[:80])

    for c in ["start_x","start_y","end_x","end_y", "touch_x","touch_y","total_distance","duration", "move_count","touch_size","touch_pressure"]:
        if c in df.columns:
            df[c] = pd.to_numeric(df[c], errors="coerce")

    if {"start_x","end_x"}.issubset(df.columns): df["dx"] = df["end_x"] - df["start_x"]
    elif "touch_x" in df.columns: df["dx"] = df["touch_x"].diff().fillna(0.0)
    else: df["dx"] = np.nan

    if {"start_y","end_y"}.issubset(df.columns): df["dy"] = df["end_y"] - df["start_y"]
    elif "touch_y" in df.columns: df["dy"] = df["touch_y"].diff().fillna(0.0)
    else: df["dy"] = np.nan

    if "total_distance" in df.columns: dist = df["total_distance"]
    else: dist = np.sqrt((df["dx"].astype(float)**2 + df["dy"].astype(float)**2))
    dur_ms = df["duration"] if "duration" in df.columns else np.nan
    dur_s = pd.to_numeric(dur_ms, errors="coerce") / 1000.0
    with np.errstate(divide="ignore", invalid="ignore"): speed = dist / dur_s
    df["speed"] = pd.Series(speed).replace([np.inf, -np.inf], np.nan)

    if "move_count" not in df.columns: df["move_count"] = np.nan

    feature_pool = ["dx","dy","total_distance","duration","speed","move_count", "start_x","start_y","end_x","end_y", "touch_x","touch_y","touch_size","touch_pressure"]
    feature_cols = [c for c in feature_pool if c in df.columns]

    valid_cols = []
    for c in feature_cols:
        col = pd.to_numeric(df[c], errors="coerce")
        if col.notna().sum() == 0 or col.nunique(dropna=True) <= 1:
            continue
        valid_cols.append(c)

    if not valid_cols:
        print("⚠️ 사용 가능한 피처 없음 → 스킵"); sys.exit(100)

    initial_rows = len(df)
    df = df[(df[valid_cols] != 0).all(axis=1)]
    rows_after_zero_filter = len(df)
    df = df.drop_duplicates(subset=valid_cols, keep='first')
    final_rows = len(df)
    skipped_for_zero = initial_rows - rows_after_zero_filter
    skipped_for_duplicates = rows_after_zero_filter - final_rows

    if final_rows == 0:
        print("⚠️ 필터링 후 남은 데이터 없음 → 학습 스킵"); sys.exit(100)

    df[valid_cols] = df[valid_cols].apply(pd.to_numeric, errors="coerce").fillna(0.0)

    scaler = MinMaxScaler()
    Xnum = scaler.fit_transform(df[valid_cols].to_numpy(dtype=np.float32))
    seqs = create_sequences(Xnum, args.seq_len)
    if seqs is None or (hasattr(seqs, "size") and seqs.size == 0) or (isinstance(seqs, (list, tuple)) and len(seqs) == 0):
        print("⚠️ 시퀀스 없음 → 스킵"); sys.exit(100)

    device = args.device
    X = torch.tensor(seqs, dtype=torch.float32).to(device)
    model = LSTMAE(n_features=len(valid_cols)).to(device)
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
            print(f"[drag] {ep}/{args.epochs} loss={loss.item():.6f}")

    # ── 학습 데이터 오차 분포 → q01/q99 계산 ──
    model.eval()
    with torch.no_grad():
        recon = model(X)
        mse = torch.mean((X - recon) ** 2, dim=(1, 2)).cpu().numpy()
        
    # [수정] Quantile 기준을 1% -> 0.2%로 변경 (0.01 -> 0.002, 0.99 -> 0.998)
    q01 = float(np.quantile(mse, 0.002))
    q99 = float(np.quantile(mse, 0.998))
    print(f"[drag] train quantiles (0.2%) → q01={q01:.6f}, q99={q99:.6f}")

    # 저장 (state_dict + q01/q99)
    torch.save({"state_dict": model.state_dict(), "q01": q01, "q99": q99}, args.out_model)
    joblib.dump({"scaler": scaler, "features": valid_cols, "seq_len": args.seq_len}, args.out_scaler)
    print(f"✅ 저장: {args.out_model} / {args.out_scaler}")

    print("\n--- 로그 필터링 통계 ---")
    print(f"초기 로그 수: {initial_rows}개")
    print(f"  - 0값 포함으로 제외: {skipped_for_zero}개")
    print(f"  - 중복 값으로 제외: {skipped_for_duplicates}개")
    print(f"최종 학습 사용 로그 수: {final_rows}개")

    sys.exit(0)

if __name__ == "__main__":
    main()