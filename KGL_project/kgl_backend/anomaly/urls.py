# anomaly/urls.py
from django.urls import path
from .views import AnomalyResultList, MobileAnomalyUpdates

urlpatterns = [
    path('anomalies/', AnomalyResultList.as_view(), name='anomalies'),
    path('anomaly-results/', AnomalyResultList.as_view(), name='anomaly-results'),
    path('mobile/anomaly/updates', MobileAnomalyUpdates.as_view(), name='mobile-anomaly-updates'),
]
