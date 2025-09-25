# anomaly/models.py
from django.db import models
from django.conf import settings

class AnomalyResult(models.Model):

    behavior_log = models.ForeignKey('behavior.UserBehaviorLog', on_delete=models.CASCADE)

    modality = models.CharField(max_length=50, null=True, blank=True)
    timestamp = models.DateTimeField(null=True, blank=True)

    anomaly_score = models.FloatField()
    is_anomaly = models.BooleanField()
    created_at = models.DateTimeField(auto_now_add=True)

    DETECTION_CHOICES = (
        ('iforest', 'IsolationForest'),
        ('lstm', 'LSTM'),
        ('hybrid', 'Hybrid'),
    )
    detection_method = models.CharField(
        max_length=20,
        choices=DETECTION_CHOICES,
        default='hybrid',
        db_index=True,
        help_text="Anomaly detected by: iForest, LSTM, iForest+LSTM",
    )

    class Meta:
        indexes = [
            models.Index(fields=['modality']),
            models.Index(fields=['timestamp']),
            models.Index(fields=['created_at']),
        ]

    def __str__(self):
        return f"Anomaly ({'True' if self.is_anomaly else 'False'}) - Score: {self.anomaly_score:.2f}"
