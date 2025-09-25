### 1. 프로젝트 배경
최근 정보 보안 환경은 '절대 신뢰하지 말고, 항상 검증하라'는 제로 트러스트(Zero Trust) 모델을 요구하고 있습니다. 기존의 정적 인증 방식은 계정 탈취나 내부자 위협 같은 새로운 공격에 취약합니다.
본 프로젝트는 이러한 문제를 해결하기 위해, 사용자의 스마트폰 사용 패턴을 분석하여 보안 위협을 실시간으로 탐지하고 네트워크 접근을 동적으로 제어하는 Policy Engine 시스템을 개발했습니다. 사용자의 고유한 터치, 센서 움직임, 네트워크 로그를 UEBA(사용자 및 엔티티 행동 분석) 기술로 분석하여, 기존 보안 시스템이 탐지하기 어려운 이상 행위를 포착하고 즉각적으로 대응하는 것을 목표로 합니다.

### 2. 개발 목표

#### 2.1. 목표 및 세부 내용

본 프로젝트의 최종 목표는 모바일 기기에서 사용자의 행동 로그를 실시간으로 수집 및 분석하고, 이상 징후를 탐지하여 네트워크 접근을 동적으로 제어하는 
지능형 NAC(네트워크 접근 제어) 시스템을 개발하는 것입니다. 주요 기능 및 세부 목표는 다음과 같습니다.

1)에이전트 기반 실시간 로그 수집 

안드로이드 기기에서 발생하는 터치(Touch), 센서(Sensor), 네트워크(Network) 로그를 에이전트를 통해 실시간으로 수집합니다.
수집된 모든 로그는 JSON 형식으로 정규화하여 백엔드 서버로 전송합니다.


2)머신러닝 기반 이상 행위 탐지 

Isolation Forest와 LSTM Auto-Encoder를 결합한 하이브리드 모델을 사용하여, 개별 이벤트의 이상 여부와 시계열 데이터의 패턴 변칙성을 동시에 탐지합니다.
이를 통해 단일 모델 대비 안정성과 탐지율을 향상시킵니다.


3)동적 정책 엔진 및 백엔드 구현 

Django와 FastAPI 기반의 백엔드 및 머신러닝 서버를 구축하여 로그 수집, 이상 탐지, 결과 저장을 자동화합니다.
탐지된 이상 수준에 따라 네트워크 차단, 잠금 화면 전환 등 보안 정책을 실시간으로 적용합니다.

4)관리자용 대시보드 및 사용자 피드백 

React 기반의 웹 대시보드를 통해 관리자가 시스템의 보안 상태와 이상 로그를 직관적으로 모니터링할 수 있도록 지원합니다.
이상 징후가 탐지되면 안드로이드 클라이언트에서 사용자에게 즉시 알리고, 네트워크 차단등의 대응 선택지를 제공합니다.

#### 2.2. 기존 서비스 대비 차별성

기존 NAC 연구 및 솔루션은 주로 네트워크 접속 시점의 일회성 인증에 의존하여, 내부자 위협이나 계정 탈취와 같이 인증 후에 발생하는 행동 변화를 탐지하기 어렵다는 한계가 있었습니다. 본 프로젝트는 이러한 한계를 극복하기 위해 다음과 같은 차별성을 가집니다.

1)다중 모달리티 실시간 행동 분석

기존 시스템이 주로 네트워크 로그에 의존했던 것과 달리, 본 시스템은 터치, 센서, 네트워크라는 세 가지 다른 종류의 데이터를 실시간으로 수집하여 사용자의 실제 행동 패턴을 종합적으로 분석합니다. 이는 정적 인증 수단만으로는 포착하기 어려운 미묘한 이상 행위까지 탐지할 수 있게 합니다.


2)하이브리드 이상 탐지 모델 적용

단일 알고리즘의 한계를 보완하기 위해 통계 기반의 iForest와 시계열 패턴 분석에 강한 LSTM을 결합한 하이브리드 모델을 사용합니다. 이를 통해 개별 로그의 특이점과 시간적 맥락을 함께 고려하여 탐지 정확성과 안정성을 모두 높였습니다.


3)'탐지-대응-시각화' 워크플로우

단순히 이상 행위를 탐지하는 수준을 넘어, 탐지 결과를 정책 엔진과 즉시 연동하여 네트워크를 차단하거나 잠금 화면으로 전환하는 등 동적 제어를 수행합니다. 동시에 관리자는 웹 대시보드를 통해 모든 과정을 직관적으로 모니터링하고 분석할 수 있어, 실시간 로그 수집부터 최종 대응까지 일련의 보안 프로세스를 하나의 시스템에서 관리합니다.


#### 2.3. 사회적 가치 도입 계획

본 프로젝트는 고도화되는 사이버 위협 환경 속에서 개인과 사회의 디지털 자산을 보호함으로써 다음과 같은 사회적 가치 창출에 기여하고자 합니다.

1)안전한 디지털 환경 구축

원격 근무, 모바일 뱅킹 등 비대면 디지털 사회로의 전환이 가속화되면서 개인정보 유출 및 계정 탈취 범죄가 심각한 사회 문제로 대두되고 있습니다. 본 시스템은 사용자의 고유한 행동 패턴을 기반으로 제로데이 공격이나 내부자 위협 등 예측하기 어려운 보안 위협을 선제적으로 탐지하여 시민들이 안심하고 디지털 서비스를 이용할 수 있는 신뢰도 높은 사이버 환경을 조성하는 데 기여합니다.

2)지속 가능한 보안 생태계 조성

기존의 정적이고 경계 기반의 보안 모델은 새로운 공격 벡터 앞에서 크게 효과적이지 않습니다. 본 연구는 '항상 검증(Always Verify)'이라는 제로 트러스트(ZTNA) 보안 패러다임을 모바일 환경에 구현한 것입니다. 이는 지속적으로 변화하는 위협에 능동적으로 적응할 수 있는 차세대 보안 모델의 기반 기술로서, 향후 노트북, IoT 기기 등 다양한 엔드포인트로 확장하여  지속 가능한 디지털 보안 생태계를 구축하는 데 기여할 수 있습니다.

### 4. 시스템 구성도

시스템은 사용자의 행동 로그를 수집, 분석, 탐지하며 관리자에게 시각화하고 사용자에게 피드백을 제공하는 End-to-End 파이프라인 구조로 설계되었습니다.
Android App (Agent): 사용자의 터치, 센서, 네트워크 로그를 실시간으로 수집하여 백엔드 서버로 전송합니다.
Django Backend: 로그를 수신 및 전처리하고, 데이터베이스에 저장하며, 머신러닝 서버에 분석을 요청합니다.
ML Server (FastAPI): Isolation Forest와 LSTM Auto-Encoder 하이브리드 모델을 통해 로그의 이상 여부를 실시간으로 탐지합니다.
Anomaly DB: 탐지된 이상 행위 결과를 저장합니다.
Dashboard (React): 관리자가 이상 탐지 로그를 직관적으로 모니터링할 수 있도록 시각화합니다.
Mobile Alert: 이상 행위가 탐지되면 안드로이드 앱에서 사용자에게 즉시 알리고 보안 조치를 안내합니다.
<img width="797" height="457" alt="image" src="https://github.com/user-attachments/assets/4c17d994-b51d-45f0-874f-eb2b0ca5e672" />



### 4. 핵심 기술 개발 결과

#### 4.1. 전체 시스템 아키텍처 

<img width="1264" height="720" alt="image" src="https://github.com/user-attachments/assets/a3b050dc-d331-4c67-86ed-c866ba7cd8da" />

#### 4.2. 주요 기능

##### 4.2.1. 데이터 수집 (Android Agent)

안드로이드 에이전트를 통해 3가지 핵심 데이터를 실시간으로 수집합니다.
Touch: 사용자의 고유한 습관이 반영된 터치 압력, 드래그 패턴, 지속 시간 등을 수집합니다.

<img width="711" height="488" alt="image" src="https://github.com/user-attachments/assets/404a8f10-efa4-443c-b496-d4829f438cd4" />

Sensor: 가속도계와 자이로스코프 센서를 이용해 기기 흔들림, 걸음걸이 등 물리적 행동 패턴을 수집합니다.

<img width="357" height="502" alt="image" src="https://github.com/user-attachments/assets/f23bca64-b9d1-41c4-8483-70a8106a3026" />

Network: GPS 좌표, 접속 정보를 수집하여 이상현상을 탐지합니다.

<img width="342" height="262" alt="image" src="https://github.com/user-attachments/assets/eeb654c9-045e-4964-a347-af599721ad92" />

##### 4.2.2. 이상 탐지 모델 (Hybrid Model)

두 가지 머신러닝 모델을 결합한 하이브리드 아키텍처를 통해 탐지 정확성과 안정성을 높였습니다.
Isolation Forest: 트리 기반의 빠른 이상 탐지 모델로, 개별 로그 데이터의 통계적 이상치를 탐지합니다.
LSTM Auto-Encoder: 사용자의 행동 순서(Sequence)를 학습하여, 시계열 데이터의 패턴에서 벗어나는 변칙적인 행위를 탐지합니다.
Hybrid 융합: 두 모델 중 하나라도 이상을 탐지하면 최종 '이상'으로 판단하는 OR 논리를 적용하여 탐지율을 극대화했습니다.

##### 4.2.3. 동적 대응 및 피드백 (Android & Dashboard)

이상 탐지 결과에 따라 즉각적인 보안 조치가 실행됩니다.

사용자 대응:
네트워크 이상 탐지 시: 사용자에게 '비행기 모드 전환', 'Wi-Fi 차단' 등의 선택지를 팝업으로 제공합니다.
터치/센서 이상 탐지 시: 비정상적인 사용으로 판단하여 자동으로 스마트폰을 잠금 상태로 전환합니다.

<img width="695" height="207" alt="image" src="https://github.com/user-attachments/assets/e74bbdce-ac69-40fd-b7ab-d64140f69792" />

관리자 모니터링:
React 기반 대시보드를 통해 전체 시스템의 위험도를 실시간으로 확인하고, 이상 로그를 필터링하며 상세 분석할 수 있습니다.
메인 대시보드: 시스템의 전반적인 위험도를 나타내는 리스크 게이지, 최근 이상 점수 변화를 보여주는 트렌드 차트, 최신 로그 목록을 통해 현재 상태를 한눈에 파악할 수 있습니다.

<img width="790" height="418" alt="image" src="https://github.com/user-attachments/assets/378029f0-02b5-4b58-a0ab-e27553533831" />

Logs 페이지: 모든 로그를 상세히 조회하고, 모달리티별, 이상 로그만 필터링하는 등 심층 분석을 위한 강력한 필터링 기능을 제공합니다.

<img width="777" height="352" alt="image" src="https://github.com/user-attachments/assets/6ad62a9a-05c8-490c-8314-f645651de14e" />

Stats 페이지: 가장 위험도가 높은 로그 Top 5 목록 등을 제공하여, 관리자가 가장 시급한 위협부터 효율적으로 분석하고 대응할 수 있도록 지원합니다

<img width="770" height="292" alt="image" src="https://github.com/user-attachments/assets/729f355b-b820-4c99-9775-9d298d4ac3dd" />

#### 4.3. 성능평가

모델 성능 평가 결과, 평균 F1-Score 0.8961, 평균 재현율(Recall) 0.9160을 기록하여 대부분의 이상 행위를 성공적으로 탐지하는 성능을 확인했습니다. 특히, 사용자의 무의식적 패턴이 반영된 센서 데이터에서 가장 높은 탐지 성능(F1-Score 0.9668)을 보였습니다.
1)LSTM-AE 성능평가 결과

<img width="727" height="291" alt="image" src="https://github.com/user-attachments/assets/26d29671-3452-42fb-9fb7-79e70d3c35d3" />

2)Isolation-Forest 성능평가 결과

<img width="742" height="260" alt="image" src="https://github.com/user-attachments/assets/e521ef73-a1b9-49a6-8a0f-cb43e66d648f" />

#### 4.4. 디렉터리 구조 

<img width="500" height="592" alt="image" src="https://github.com/user-attachments/assets/ec0f478a-f18d-4d6d-94b4-1fe1089838f5" />

### 5. 향후 연구 방향 

본 연구를 기반으로 다음과 같은 방향으로 시스템을 고도화할 수 있습니다.
모델 고도화: GRU, Transformer 등 더 복잡한 시계열 패턴을 학습할 수 있는 최신 딥러닝 모델을 도입하고, 데이터 증강 및 적대적 훈련(Adversarial Training) 기법을 적용하여 모델의 정확성 향상.
실시간 통신 개선: 현재의 HTTP 폴링 방식을 WebSocket 기반의 Push 알림으로 전환하여, 이상 행위 발생 즉시 지연 없이 사용자에게 알림을 전달하는 완전한 실시간 시스템을 구축.
탐지 범위 확장: 네트워크 위협 탐지를 GeoIP, 기지국 정보, VPN/Proxy 패턴 학습 등으로 강화하고 , 분석 대상을 모바일뿐만 아니라 노트북, IoT 기기 등 다양한 엔드포인트로 확장하여 통합적인 보안 체계를 구축.

### 6. 기술 스택

Backend: Python, Django, Django REST Framework 

Machine Learning: Python, FastAPI, Scikit-learn, PyTorch 

Dashboard: React, TypeScript 

Mobile Agent: Android (Java/Kotlin) 

Database: SQLite, PostgreSQL 

```
$ ./install_and_build.sh
```
### 7. 소개자료

[2025전기_KGL_발표자료.pdf](https://github.com/user-attachments/files/22529991/2025._KGL_.pdf)

### 8. 팀소개

#### 지도교수: 최윤호 교수님

#### 권태현
##### xogus0065@naver.com
##### 프론트엔드 및 LSTM-AE 모델 개발

#### 구현서
##### hyeonseo0524@naver.com
##### 안드로이드 및 Isolation-Forest 모델 개발

#### 이승원
##### swon9570@naver.com
##### policy engine 백앤드 개발

### 9. 참고 문헌

1. Mahmoud Said Elsayed, Nhien-An Le-Khac, Soumyabrata Dev, and Anca Delia Jurcut. Network Anomaly Detection Using LSTM Based Autoencoder. 2020.
2. O. I. Provotar, Y. M. Linder, and M. M. Veres. Unsupervised Anomaly Detection in Time Series Using LSTM-Based Autoencoders. 2019 IEEE International Conference on Advanced Trends in Information Theory (ATIT), Kyiv, Ukraine, 2019, pp. 513-517. doi: 10.1109/ATIT49449.2019.9030505.
3. Sensor-Based Continuous Authentication of Smartphones’ Users Using Behavioral Biometrics: A Contemporary Survey 
4. Mobile User Authentication Using Statistical Touch Dynamics Images 
5. A User and Entity Behavior Analytics Log Data Set for Anomaly Detection in Cloud Computing 
6. A Dynamic Access Control Model Based on Attributes and Intro VAE

