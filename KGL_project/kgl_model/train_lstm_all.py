# train_all.py
import subprocess
import sys

# 실행할 스크립트 순서
scripts = [
    "train_lstm_touch_pressure.py",
    "train_lstm_touch_drag.py",
    "train_lstm_sensor.py",
]

summary = {}

for script in scripts:
    print(f"\n===== 실행 시작: {script} =====\n")
    result = subprocess.run([sys.executable, script])
    if result.returncode == 0:
        summary[script] = "OK"
    elif result.returncode == 100:
        summary[script] = "SKIPPED (데이터 없음)"
    else:
        summary[script] = "FAILED"

print("\n===== 실행 요약 =====")
for script, status in summary.items():
    print(f"{script:>28} : {status}")

if all(status != "FAILED" for status in summary.values()):
    print("\n🎉 모든 학습 스크립트 완료 (실패 없음)")
else:
    print("\n⚠️ 일부 스크립트 실패")
