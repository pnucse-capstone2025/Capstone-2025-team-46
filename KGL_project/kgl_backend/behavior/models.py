from django.db import models

# Create your models here.
class UserBehaviorLog(models.Model):
    user_id = models.CharField(max_length=255)
    session_id = models.CharField(max_length=255)
    action_type = models.CharField(max_length=50)
    sequence_index = models.IntegerField()
    timestamp = models.DateTimeField()
    params = models.JSONField()
    device_info = models.JSONField()
    location = models.JSONField(null=True, blank=True)

    def __str__(self):
        return f"{self.user_id} | {self.action_type} | seq={self.sequence_index} | {self.timestamp}"
