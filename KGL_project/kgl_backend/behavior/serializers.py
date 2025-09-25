# behavior/serializers.py
from rest_framework import serializers
from .models import UserBehaviorLog

class BehaviorLogSerializer(serializers.ModelSerializer):
    class Meta:
        model = UserBehaviorLog
        fields = '__all__'
