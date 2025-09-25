# behavior/views.py
from rest_framework import viewsets, status
from rest_framework.views import APIView
from rest_framework.response import Response
from django.utils import timezone
from django.conf import settings
import logging, requests
from datetime import datetime, timezone as dt_timezone
from django.db import transaction
from django.utils.dateparse import parse_datetime
from typing import Optional, Set, List, Dict, Any

from .serializers import BehaviorLogSerializer
from .models import UserBehaviorLog
from anomaly.models import AnomalyResult

logger = logging.getLogger(__name__)

KOREA_LAT_MIN, KOREA_LAT_MAX = 33.1, 38.6
KOREA_LNG_MIN, KOREA_LNG_MAX = 124.6, 129.5

MAX_LOG_QUEUE_SIZE = 1500

def _ts(value):
    if value is None:
        return timezone.now()
    try:
        if isinstance(value, (int, float)):
            if value > 10 ** 12:
                return datetime.fromtimestamp(value / 1000, tz=dt_timezone.utc)
            return datetime.fromtimestamp(value, tz=dt_timezone.utc)
        if isinstance(value, str):
            dt = parse_datetime(value)
            return dt if dt else timezone.now()
    except Exception:
        pass
    return timezone.now()


def _call_ml_server(logs_payload, endpoint):
    try:
        for log in logs_payload:
            if isinstance(log.get('timestamp'), datetime):
                log['timestamp'] = log['timestamp'].isoformat()

        url = f"{settings.ML_SERVER_URL}/{endpoint}"
        r = requests.post(url, json=logs_payload, timeout=10)
        r.raise_for_status()
        return r.json()
    except Exception as e:
        logger.error(f"통합 ML 서버 호출 실패: {e}")
        return None


def _enforce_log_limit(
    model,
    max_count: int = MAX_LOG_QUEUE_SIZE,
    protect_pks: Optional[Set[int]] = None,
):
    try:
        current_count = model.objects.count()
        if current_count > max_count:
            num_to_delete = current_count - max_count
            qs = model.objects.order_by('pk').values_list('pk', flat=True)
            if protect_pks:
                qs = qs.exclude(pk__in=protect_pks)
            oldest_logs_pks = list(qs[:num_to_delete])

            if oldest_logs_pks:
                deleted_count, _ = model.objects.filter(pk__in=oldest_logs_pks).delete()
                logger.warning(
                    f"Log queue exceeded {max_count}. Deleted {deleted_count} oldest logs from DB (protected={len(protect_pks or [])})."
                )
    except Exception as e:
        logger.error(f"Failed to clean up old logs: {e}")



def _to_float(x, default=0.0):
    try:
        return float(x)
    except Exception:
        return default

def _to_bool(x, default=False):
    if isinstance(x, bool):
        return x
    try:
        return str(x).lower() in ("1", "true", "t", "yes", "y")
    except Exception:
        return default

def _parse_ts_or(log_ts, fallback_dt: datetime):
    dt = None
    try:
        dt = parse_datetime(log_ts) if isinstance(log_ts, str) else None
    except Exception:
        dt = None
    return dt or fallback_dt

@transaction.atomic
def persist_ml_results(behavior_logs: List[UserBehaviorLog], ml_results: List[Dict[str, Any]], method: str):
    if not behavior_logs or not ml_results:
        logger.info("persist_ml_results: nothing to persist (logs=%s, results=%s)",
                    len(behavior_logs) if behavior_logs else 0,
                    len(ml_results) if ml_results else 0)
        return 0

    by_seq = {bl.sequence_index: bl for bl in behavior_logs}
    to_create: List[AnomalyResult] = []

    for r in ml_results:
        try:
            seq = int(r.get("sequence_index"))
        except Exception:
            logger.warning("persist_ml_results: skip result without valid sequence_index: %s", r)
            continue

        bl = by_seq.get(seq)
        if not bl:
            logger.warning("persist_ml_results: matching behavior_log not found for seq=%s, result=%s", seq, r)
            continue

        modality = r.get("modality") or ""
        ts = _parse_ts_or(r.get("timestamp"), bl.timestamp)

        if method == "hybrid":
            is_anom = _to_bool(r.get("is_anomaly_combined", r.get("is_anomaly")))
            score = _to_float(r.get("anomaly_score_combined", r.get("anomaly_score")))
            det = "hybrid"
        elif method == "iforest":
            is_anom = _to_bool(r.get("is_anomaly"))
            score = _to_float(r.get("anomaly_score"))
            det = "iforest"
        else:
            is_anom = _to_bool(r.get("is_anomaly"))
            score = _to_float(r.get("anomaly_score"))
            det = (method or "unknown").lower()

        to_create.append(AnomalyResult(
            behavior_log=bl,
            modality=modality,
            timestamp=ts,
            anomaly_score=score,
            is_anomaly=is_anom,
            detection_method=det,
        ))

    if not to_create:
        logger.warning("persist_ml_results: no rows to create after mapping. (results=%d)", len(ml_results))
        return 0

    AnomalyResult.objects.bulk_create(to_create, batch_size=500)
    logger.info("persist_ml_results: created %d anomaly rows (method=%s)", len(to_create), method)
    return len(to_create)

class BehaviorLogViewSet(viewsets.ViewSet):
    def create(self, request):
        logs = request.data.get('logs')
        if logs is None or not isinstance(logs, list):
            return Response({"error": "Missing 'logs' array"}, status=400)

        created = 0
        prepared_logs: List[Dict[str, Any]] = []
        seq_to_log = {}

        with transaction.atomic():
            for item in logs:
                item['timestamp'] = _ts(item.get('timestamp') or item.get('ts'))
                ser = BehaviorLogSerializer(data=item)
                if not ser.is_valid():
                    return Response(ser.errors, status=400)

                obj = ser.save()
                created += 1
                seq_to_log[obj.sequence_index] = obj
                prepared_logs.append(item)

        ml_results = _call_ml_server(prepared_logs, 'predict')

        with transaction.atomic():
            protect_pks = {o.pk for o in seq_to_log.values()}
            _enforce_log_limit(UserBehaviorLog, MAX_LOG_QUEUE_SIZE, protect_pks=protect_pks)

            saved = persist_ml_results(list(seq_to_log.values()), ml_results or [], method="iforest")

        return Response({
            "status": "success",
            "created_count": created,
            "ml_saved_results": saved,
            "message": f"Successfully processed {created} logs and saved {saved} ML results.",
        }, status=201)


class NetworkDataIngestView(APIView):
    def post(self, request):
        data = request.data
        if not isinstance(data, list):
            return Response({"error": "Expected a JSON array"}, status=400)

        created_logs = 0
        created_anomalies = 0
        seq_to_log: Dict[int, UserBehaviorLog] = {}

        with transaction.atomic():
            for item in data:
                lat = item.get("lat") or item.get("latitude")
                lng = item.get("lng") or item.get("lon") or item.get("longitude")
                has_coords = lat is not None and lng is not None
                try:
                    if has_coords:
                        lat = float(lat); lng = float(lng)
                except Exception:
                    has_coords = False

                payload = {
                    "user_id": item.get("user_id", "device-anonymous"),
                    "session_id": item.get("session_id", "session-unknown"),
                    "action_type": "network_status",
                    "sequence_index": item.get("seq", 0),
                    "timestamp": _ts(item.get("timestamp") or item.get("ts")),
                    "params": {
                        "net_type": item.get("net_type"),
                        "rssi": item.get("rssi"),
                    },
                    "device_info": item.get("device_info", {}),
                    "location": {
                        "latitude": lat if has_coords else None,
                        "longitude": lng if has_coords else None,
                        "accuracy": item.get("accuracy"),
                    } if has_coords else None,
                }
                ser = BehaviorLogSerializer(data=payload)
                if not ser.is_valid():
                    return Response(ser.errors, status=400)

                bl = ser.save()
                created_logs += 1
                seq_to_log[bl.sequence_index] = bl

                if has_coords:
                    outside_korea = not (KOREA_LAT_MIN <= lat <= KOREA_LAT_MAX and KOREA_LNG_MIN <= lng <= KOREA_LNG_MAX)
                    AnomalyResult.objects.create(
                        behavior_log=bl,
                        modality='network',
                        timestamp=bl.timestamp,
                        anomaly_score=1.0 if outside_korea else 0.0,
                        is_anomaly=outside_korea,
                        detection_method='hybrid',
                    )
                    created_anomalies += 1

        with transaction.atomic():
            protect_pks = {o.pk for o in seq_to_log.values()}
            _enforce_log_limit(UserBehaviorLog, MAX_LOG_QUEUE_SIZE, protect_pks=protect_pks)

        return Response({
            "status": "success",
            "created_count": created_logs,
            "created_anomaly_rows": created_anomalies,
            "decision": "allow",
            "risk_score": 0.10,
            "confidence": 0.95,
            "next_check_interval": 1000,
        }, status=201)



class SensorDataIngestView(APIView):
    def post(self, request):
        data = request.data
        if not isinstance(data, list):
            return Response({"error": "Expected a JSON array"}, status=400)

        created = 0
        prepared_logs, seq_to_log = [], {}

        with transaction.atomic():
            for item in data:
                stype = item.get("type", "unknown")
                action = "sensor_accelerometer" if stype in ("accel", "accelerometer") else \
                         "sensor_gyroscope"     if stype in ("gyro", "gyroscope")     else "sensor_unknown"
                payload = {
                    "user_id": item.get("user_id", "device-anonymous"),
                    "session_id": item.get("session_id", "session-unknown"),
                    "action_type": action,
                    "sequence_index": item.get("seq", 0),
                    "timestamp": _ts(item.get("timestamp") or item.get("ts")),
                    "params": {
                        "x": item.get("x"), "y": item.get("y"), "z": item.get("z"),
                        "magnitude": item.get("magnitude"),
                    },
                    "device_info": item.get("device_info", {}),
                    "location": item.get("location", None),
                }
                ser = BehaviorLogSerializer(data=payload)
                if ser.is_valid():
                    obj = ser.save()
                    created += 1
                    seq_to_log[obj.sequence_index] = obj
                    prepared_logs.append(payload)
                else:
                    return Response(ser.errors, status=400)

        ml_results_hybrid = _call_ml_server(prepared_logs, "predict_hybrid")

        with transaction.atomic():
            protect_pks = {o.pk for o in seq_to_log.values()}
            _enforce_log_limit(UserBehaviorLog, MAX_LOG_QUEUE_SIZE, protect_pks=protect_pks)
            saved = persist_ml_results(list(seq_to_log.values()), ml_results_hybrid or [], method="hybrid")

        return Response({
            "status": "success",
            "created_count": created,
            "decision": "allow",
            "risk_score": 0.20,
            "confidence": 0.88,
            "next_check_interval": 1000,
            "ml_saved_results": saved,
        }, status=201)


class TouchDataIngestView(APIView):
    def post(self, request):
        data = request.data
        if not isinstance(data, list):
            return Response({"error": "Expected a JSON array"}, status=400)

        created = 0
        prepared_logs, seq_to_log = [], {}

        with transaction.atomic():
            for item in data:
                payload = {
                    "user_id": item.get("user_id", "device-anonymous"),
                    "session_id": item.get("session_id", "session-unknown"),
                    "action_type": item.get("action_type", "touch_unknown"),
                    "sequence_index": item.get("seq", 0),
                    "timestamp": _ts(item.get("timestamp") or item.get("ts")),
                    "params": {
                        "x": item.get("x"), "y": item.get("y"),
                        "pressure": item.get("pressure"),
                        "size": item.get("size"),
                        "duration": item.get("duration"),
                        "screen": item.get("screen"),
                        "view_id": item.get("view_id"),
                    },
                    "device_info": item.get("device_info", {}),
                    "location": item.get("location", None),
                }
                ser = BehaviorLogSerializer(data=payload)
                if ser.is_valid():
                    obj = ser.save()
                    created += 1
                    seq_to_log[obj.sequence_index] = obj
                    prepared_logs.append(payload)
                else:
                    return Response(ser.errors, status=400)

        ml_results_hybrid = _call_ml_server(prepared_logs, "predict_hybrid")

        with transaction.atomic():
            protect_pks = {o.pk for o in seq_to_log.values()}
            _enforce_log_limit(UserBehaviorLog, MAX_LOG_QUEUE_SIZE, protect_pks=protect_pks)

            saved = persist_ml_results(list(seq_to_log.values()), ml_results_hybrid or [], method="hybrid")

        return Response({
            "status": "success",
            "created_count": created,
            "decision": "allow",
            "risk_score": 0.05,
            "confidence": 0.92,
            "next_check_interval": 100,
            "ml_saved_results": saved,
        }, status=201)
