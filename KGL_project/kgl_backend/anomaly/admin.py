# anomaly/admin.py
from django.contrib import admin
from .models import AnomalyResult

@admin.register(AnomalyResult)
class AnomalyResultAdmin(admin.ModelAdmin):
    list_display = (
        "id",
        "user_id",
        "action_type",
        "sequence_index",
        "modality",
        "anomaly_score",
        "is_anomaly",
        "detection_method",
        "timestamp",
        "created_at",
    )
    list_select_related = ("behavior_log",)
    search_fields = ("behavior_log__user_id", "behavior_log__session_id")  # 검색에는 더블 언더스코어 허용
    list_filter = ("modality", "detection_method", "is_anomaly")


    def user_id(self, obj):
        return getattr(obj.behavior_log, "user_id", None)
    user_id.short_description = "User"
    user_id.admin_order_field = "behavior_log__user_id"

    def action_type(self, obj):
        return getattr(obj.behavior_log, "action_type", None)
    action_type.short_description = "Action"
    action_type.admin_order_field = "behavior_log__action_type"

    def sequence_index(self, obj):
        return getattr(obj.behavior_log, "sequence_index", None)
    sequence_index.short_description = "Seq"
    sequence_index.admin_order_field = "behavior_log__sequence_index"
