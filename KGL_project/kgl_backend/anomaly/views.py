# anomaly/views.py
from rest_framework import generics, status
from rest_framework.views import APIView
from rest_framework.response import Response
from django.utils.dateparse import parse_datetime
from django.utils.timezone import make_aware
from datetime import timezone

from .models import AnomalyResult
from .serializers import AnomalyResultSerializer, MobileAnomalySerializer


class AnomalyResultList(generics.ListAPIView):
    queryset = AnomalyResult.objects.all().order_by('-timestamp').select_related('behavior_log')
    serializer_class = AnomalyResultSerializer



class MobileAnomalyUpdates(APIView):
    authentication_classes = []
    permission_classes = []

    def get(self, request):

        qs = AnomalyResult.objects.all().select_related('behavior_log')
        only_abnormal = request.query_params.get("only_abnormal", "true").lower() != "false"
        if only_abnormal:
            qs = qs.filter(is_anomaly=True)

        since_id = request.query_params.get("since_id")
        if since_id:
            try:
                qs = qs.filter(id__gt=int(since_id))
            except ValueError:
                pass

        user_id = request.query_params.get("user_id")
        if user_id:
            field_names = {f.name for f in AnomalyResult._meta.get_fields()}
            if "user_id" in field_names:
                qs = qs.filter(user_id=user_id)
            else:
                qs = qs.filter(behavior_log__user_id=user_id)

        modality = request.query_params.get("modality")
        if modality:
            qs = qs.filter(modality=modality)

        created_after = request.query_params.get("created_after")
        if created_after:
            dt = parse_datetime(created_after)
            if dt and dt.tzinfo is None:
                dt = make_aware(dt, timezone=timezone.utc)
            if dt:
                qs = qs.filter(created_at__gt=dt)

        try:
            limit = min(max(int(request.query_params.get("limit", 100)), 1), 500)
        except ValueError:
            limit = 100
        qs = qs.order_by("id")[:limit]

        if not qs.exists():
            return Response(status=status.HTTP_204_NO_CONTENT)

        data = MobileAnomalySerializer(qs, many=True).data
        last_id = data[-1]["id"]
        etag = f'W/"mobile-anomaly-{last_id}"'
        if request.headers.get("If-None-Match") == etag:
            return Response(status=status.HTTP_304_NOT_MODIFIED)

        resp = Response({"last_id": last_id, "items": data}, status=status.HTTP_200_OK)
        resp["ETag"] = etag
        return resp
