### 1. 프로젝트 소개
최근 정보 보안 환경은 '절대 신뢰하지 말고, 항상 검증하라'는 제로 트러스트(Zero Trust) 모델을 요구하고 있습니다. 기존의 정적 인증 방식은 계정 탈취나 내부자 위협 같은 새로운 공격에 취약합니다.
본 프로젝트는 이러한 문제를 해결하기 위해, 사용자의 스마트폰 사용 패턴을 분석하여 보안 위협을 실시간으로 탐지하고 네트워크 접근을 동적으로 제어하는 Policy Engine 시스템을 개발했습니다. 사용자의 고유한 터치, 센서 움직임, 네트워크 로그를 UEBA(사용자 및 엔티티 행동 분석) 기술로 분석하여, 기존 보안 시스템이 탐지하기 어려운 이상 행위를 포착하고 즉각적으로 대응하는 것을 목표로 합니다.

### 2. 팀소개

지도교수: 최윤호 교수님

권태현, xogus0065@naver.com, 프론트엔드 및 LSTM-AE 모델 개발
구현서, hyeonseo0524@naver.com, 안드로이드 및 Isolation-Forest 모델 개발
이승원, swon9570@naver.com, policy engine 백앤드 개발



### 3. 시스템 구성도

시스템은 사용자의 행동 로그를 수집, 분석, 탐지하며 관리자에게 시각화하고 사용자에게 피드백을 제공하는 End-to-End 파이프라인 구조로 설계되었습니다.
Android App (Agent): 사용자의 터치, 센서, 네트워크 로그를 실시간으로 수집하여 백엔드 서버로 전송합니다.
Django Backend: 로그를 수신 및 전처리하고, 데이터베이스에 저장하며, 머신러닝 서버에 분석을 요청합니다.
ML Server (FastAPI): Isolation Forest와 LSTM Auto-Encoder 하이브리드 모델을 통해 로그의 이상 여부를 실시간으로 탐지합니다.
Anomaly DB: 탐지된 이상 행위 결과를 저장합니다.
Dashboard (React): 관리자가 이상 탐지 로그를 직관적으로 모니터링할 수 있도록 시각화합니다.
Mobile Alert: 이상 행위가 탐지되면 안드로이드 앱에서 사용자에게 즉시 알리고 보안 조치를 안내합니다.
<img width="797" height="457" alt="image" src="https://github.com/user-attachments/assets/4c17d994-b51d-45f0-874f-eb2b0ca5e672" />



### 4. 핵심 기술 및 구현

4.1 데이터 수집 (Android Agent)

안드로이드 에이전트를 통해 3가지 핵심 데이터를 실시간으로 수집합니다.
Touch: 사용자의 고유한 습관이 반영된 터치 압력, 드래그 패턴, 지속 시간 등을 수집합니다.
<img width="711" height="488" alt="image" src="https://github.com/user-attachments/assets/404a8f10-efa4-443c-b496-d4829f438cd4" />
Sensor: 가속도계와 자이로스코프 센서를 이용해 기기 흔들림, 걸음걸이 등 물리적 행동 패턴을 수집합니다.
<img width="357" height="502" alt="image" src="https://github.com/user-attachments/assets/f23bca64-b9d1-41c4-8483-70a8106a3026" />
Network: GPS 좌표, 접속 정보를 수집하여 이상현상을 탐지합니다.
<img width="342" height="262" alt="image" src="https://github.com/user-attachments/assets/eeb654c9-045e-4964-a347-af599721ad92" />

4.2 이상 탐지 모델 (Hybrid Model)

두 가지 머신러닝 모델을 결합한 하이브리드 아키텍처를 통해 탐지 정확성과 안정성을 높였습니다.
Isolation Forest: 트리 기반의 빠른 이상 탐지 모델로, 개별 로그 데이터의 통계적 이상치를 탐지합니다.
LSTM Auto-Encoder: 사용자의 행동 순서(Sequence)를 학습하여, 시계열 데이터의 패턴에서 벗어나는 변칙적인 행위를 탐지합니다.
Hybrid 융합: 두 모델 중 하나라도 이상을 탐지하면 최종 '이상'으로 판단하는 OR 논리를 적용하여 탐지율을 극대화했습니다.

4.3 동적 대응 및 피드백 (Android & Dashboard)

이상 탐지 결과에 따라 즉각적인 보안 조치가 실행됩니다.

사용자 대응:
네트워크 이상 탐지 시: 사용자에게 '비행기 모드 전환', 'Wi-Fi 차단' 등의 선택지를 팝업으로 제공합니다.
터치/센서 이상 탐지 시: 비정상적인 사용으로 판단하여 자동으로 스마트폰을 잠금 상태로 전환합니다.
<img width="695" height="207" alt="image" src="https://github.com/user-attachments/assets/e74bbdce-ac69-40fd-b7ab-d64140f69792" />

관리자 모니터링:
React 기반 대시보드를 통해 전체 시스템의 위험도를 실시간으로 확인하고, 이상 로그를 필터링하며 상세 분석할 수 있습니다.
<img width="790" height="418" alt="image" src="https://github.com/user-attachments/assets/378029f0-02b5-4b58-a0ab-e27553533831" />

### 5. 성능 평가

모델 성능 평가 결과, 평균 F1-Score 0.8961, 평균 재현율(Recall) 0.9160을 기록하여 대부분의 이상 행위를 성공적으로 탐지하는 성능을 확인했습니다. 특히, 사용자의 무의식적 패턴이 반영된 센서 데이터에서 가장 높은 탐지 성능(F1-Score 0.9668)을 보였습니다.
1)LSTM-AE 성능평가 결과
<img width="727" height="291" alt="image" src="https://github.com/user-attachments/assets/26d29671-3452-42fb-9fb7-79e70d3c35d3" />
2)Isolation-Forest 성능평가 결과
<img width="742" height="260" alt="image" src="https://github.com/user-attachments/assets/e521ef73-a1b9-49a6-8a0f-cb43e66d648f" />

### 6. 기술 스택

Backend: Python, Django, Django REST Framework 
Machine Learning: Python, FastAPI, Scikit-learn, PyTorch 
Dashboard: React, TypeScript 
Mobile Agent: Android (Java/Kotlin) 


Database: SQLite, PostgreSQL 

```
$ ./install_and_build.sh
```
