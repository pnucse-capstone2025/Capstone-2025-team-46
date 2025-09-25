# behavior/urls.py
from django.urls import path
from .views import (
    BehaviorLogViewSet,
    NetworkDataIngestView, SensorDataIngestView, TouchDataIngestView
)
from rest_framework.routers import DefaultRouter
from rest_framework.response import Response
from rest_framework.views import APIView


class CustomAPIRootView(APIView):
    def get(self, request, *args, **kwargs):
        router_urls = {
            'behavior-logs': request.build_absolute_uri('behavior-logs/'),
        }

        extra_urls = {
            'network-data': request.build_absolute_uri('network-data/'),
            'sensor-data': request.build_absolute_uri('sensor-data/'),
            'touch-data': request.build_absolute_uri('touch-data/'),
        }

        return Response({**router_urls, **extra_urls})


class CustomRouter(DefaultRouter):
    def get_api_root_view(self, api_urls=None):
        return CustomAPIRootView.as_view()


router = CustomRouter()
router.register(r'behavior-logs', BehaviorLogViewSet, basename='behavior-log')

urlpatterns = router.urls + [
    path("network-data/", NetworkDataIngestView.as_view(), name="network-data"),
    path("sensor-data/", SensorDataIngestView.as_view(), name="sensor-data"),
    path("touch-data/", TouchDataIngestView.as_view(), name="touch-data"),
]