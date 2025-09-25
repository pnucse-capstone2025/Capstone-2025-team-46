from django.http import HttpResponse
from django.urls import reverse

def index(request):
    html = f"""
    <html>
      <head>
        <title>NAC Backend</title>
      </head>
      <body>
        <h1>✅ NAC Backend is Running!</h1>
        <p>Welcome to the Network Access Control Backend.</p>
        <ul>
          <li><a href="{reverse('admin:index')}">Django Admin</a></li>
          <li><a href="/api/">API Root</a></li>
          <li><a href="/swagger/">Swagger Docs</a></li>
        </ul>
        <p>Version: 1.0.0</p>
      </body>
    </html>
    """
    return HttpResponse(html)

from django.http import JsonResponse

def api_index(_request):
    return JsonResponse({
        "endpoints": {
            "behavior_batch": "/api/behavior-logs/",
            "network_data":   "/api/network-data/",
            "sensor_data":    "/api/sensor-data/",
            "touch_data":     "/api/touch-data/",
            "ml_callback":    "/api/ml-callback/",
        },
        "method": "POST",
        "note": "각 엔드포인트는 JSON body를 POST로 받습니다.",
    })
from django.http import JsonResponse
from django.urls import reverse

def api_root(request):
    def absurl(name):
        return request.build_absolute_uri(reverse(name))

    return JsonResponse({
        "behavior_batch": absurl('behavior-batch'),
        "network_data":   absurl('network-data'),
        "sensor_data":    absurl('sensor-data'),
        "touch_data":     absurl('touch-data'),
        "ml_callback":    absurl('ml-callback'),
    }, json_dumps_params={"ensure_ascii": False})
