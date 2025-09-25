# anomaly/serializers.py
from rest_framework import serializers
from .models import AnomalyResult

class AnomalyResultSerializer(serializers.ModelSerializer):
    class Meta:
        model = AnomalyResult
        fields = '__all__'

class MobileAnomalySerializer(serializers.ModelSerializer):
    class Meta:
        model = AnomalyResult
        fields = ("id", "modality", "is_anomaly", "anomaly_score", "timestamp", "created_at")