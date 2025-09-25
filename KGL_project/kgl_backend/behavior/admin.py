from django.contrib import admin
from .models import UserBehaviorLog

@admin.register(UserBehaviorLog)
class UserBehaviorLogAdmin(admin.ModelAdmin):
        list_display = (
            "id",
            "user_id",
            "action_type",
            "sequence_index",
            "timestamp",
            "session_id",
        )