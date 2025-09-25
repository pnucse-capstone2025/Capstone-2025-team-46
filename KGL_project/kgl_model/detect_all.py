import json
import time

# 분산된 탐지 모듈 임포트
import detect_sensor
import detect_touch_drag
import detect_touch_pressure

FINAL_REPORT_PATH = "final_anomaly_report.json"

def main():
    """
    개발자용 실행 스크립트.
    분산된 각 탐지 모듈을 순차적으로 실행하고 결과를 통합합니다.
    """
    start_time = time.time()
    print(f"{'='*25} STARTING ANOMALY DETECTION SUITE {'='*25}\n")
    
    all_anomalies = []
    
    # 1. 센서 이상 탐지 실행
    try:
        sensor_anomalies = detect_sensor.main()
        if sensor_anomalies:
            all_anomalies.extend(sensor_anomalies)
    except Exception as e:
        print(f"❌ Sensor detection script failed: {e}")
    print("\n" + "="*75 + "\n")

    # 2. 터치 드래그 이상 탐지 실행
    try:
        drag_anomalies = detect_touch_drag.main()
        if drag_anomalies:
            all_anomalies.extend(drag_anomalies)
    except Exception as e:
        print(f"❌ Touch Drag detection script failed: {e}")
    print("\n" + "="*75 + "\n")

    # 3. 터치 압력 이상 탐지 실행
    try:
        pressure_anomalies = detect_touch_pressure.main()
        if pressure_anomalies:
            all_anomalies.extend(pressure_anomalies)
    except Exception as e:
        print(f"❌ Touch Pressure detection script failed: {e}")
    
    # 최종 요약
    print(f"\n\n{'='*30} OVERALL SUMMARY {'='*30}")
    
    total_anomalies = len(all_anomalies)
    
    if total_anomalies > 0:
        print(f"🚨 Total anomalies found across all modalities: {total_anomalies}")
        # 모달리티별 통계
        counts = {}
        for anom in all_anomalies:
            modality = anom.get("modality", "unknown")
            counts[modality] = counts.get(modality, 0) + 1
        
        print("\n--- Breakdown by Modality ---")
        for modality, count in counts.items():
            print(f"  - {modality}: {count} anomalies")
            
        # 최종 결과 파일 저장
        with open(FINAL_REPORT_PATH, 'w', encoding='utf-8') as f:
            json.dump(all_anomalies, f, indent=4, ensure_ascii=False)
        print(f"\n✅ All findings have been aggregated into '{FINAL_REPORT_PATH}'")
        
    else:
        print("\n🎉 Excellent! No anomalies were detected in any log files.")

    end_time = time.time()
    print(f"\nTotal execution time: {end_time - start_time:.2f} seconds.")
    print("="*75)


if __name__ == "__main__":
    main()