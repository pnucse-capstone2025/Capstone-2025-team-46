# utils.py
import os, json
import numpy as np
import pandas as pd

def _json_safeload_one_file(p):
    """
    단일 파일 로드: 우선 json.load 시도 -> 실패 시 JSONL(라인별) 파싱 fallback.
    """
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
    """
    파일 또는 폴더에서 json 로드
    - filename_contains: 파일명에 이 문자열이 포함된 경우만 로드
    """
    paths = []
    if os.path.isfile(path):
        base = os.path.basename(path)
        if (filename_contains is None) or (filename_contains in base):
            paths.append(path)
    else:
        for root, _, files in os.walk(path):
            for file in files:
                if not file.lower().endswith((".json", ".log")): # .log 확장자도 포함
                    continue
                if filename_contains and filename_contains not in file:
                    continue
                paths.append(os.path.join(root, file))

    all_data = []
    for p in paths:
        data = _json_safeload_one_file(p)
        if data is None:
            print(f"[ERROR] {os.path.basename(p)} 읽기 실패(포맷 미지원 또는 손상)")
            continue
        # [수정] load_json_logs는 파일 내용 전체를 반환하므로, 리스트의 리스트가 되지 않게 extend 사용
        if isinstance(data, list):
            all_data.extend(data)
        else:
            all_data.append(data)
            
    return all_data

def parse_touch(logs):
    """
    [수정] 단순 리스트 형태의 로그를 직접 처리하도록 수정
    """
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
            "touch_x": gv("x"), "touch_y": gv("y"), "touch_size": gv("size"), "touch_pressure": gv("pressure"),
            "start_x": gv("start_x"), "start_y": gv("start_y"), "end_x": gv("end_x"), "end_y": gv("end_y"),
            "total_distance": gv("total_distance"), "duration": gv("duration"),
            "move_count": gv("move_count"), "drag_direction": gv("drag_direction", None),
        })

    if not rows:
        return pd.DataFrame()

    df = pd.DataFrame(rows)
    df["ts"] = pd.to_numeric(df["ts"], errors="coerce").fillna(0).astype(np.int64)

    if "touch_x" in df.columns and "touch_y" in df.columns:
        df["dx"] = df["touch_x"].diff().fillna(0)
        df["dy"] = df["touch_y"].diff().fillna(0)
        df["speed"] = np.sqrt(df["dx"] ** 2 + df["dy"] ** 2)
    else:
        df["dx"], df["dy"], df["speed"] = 0.0, 0.0, 0.0

    return df

def parse_sensor(logs):
    """
    [수정] 단순 리스트 형태의 로그를 직접 처리하도록 수정
    """
    rows = []
    if not isinstance(logs, list):
        return pd.DataFrame()

    for idx, item in enumerate(logs):
        if not isinstance(item, dict):
            continue
        ts_val = item.get("ts") or item.get("timestamp") or idx
        ttype = item.get("type", "unknown")
        rows.append({
            "ts": ts_val,
            "type": ttype, # [수정] pivot을 위해 type 컬럼 추가
            "x": item.get("x", np.nan),
            "y": item.get("y", np.nan),
            "z": item.get("z", np.nan)
        })

    if not rows:
        return pd.DataFrame()
    
    df = pd.DataFrame(rows)
    df["ts"] = pd.to_numeric(df["ts"], errors="coerce").fillna(0).astype(np.int64)

    # [수정] pivot_table을 사용하여 wide-format으로 변환
    if "type" in df.columns:
        pivot_df = df.pivot_table(index='ts', columns='type', values=['x', 'y', 'z'])
        pivot_df.columns = [f'{col[1]}_{col[0]}' for col in pivot_df.columns]
        return pivot_df.reset_index()
    else: # type 컬럼이 없는 구형 데이터 호환
        return df.groupby("ts").mean(numeric_only=True).reset_index()

def create_sequences(arr, seq_len):
    if arr is None or len(arr) < 2 or seq_len is None or seq_len < 2:
        return np.array([]) # 빈 numpy 배열 반환
    if len(arr) < seq_len:
        return np.array([])
    seqs = [arr[i:i+seq_len] for i in range(len(arr) - seq_len + 1)]
    return np.array(seqs) if seqs else np.array([])