from django.contrib import admin
from django.urls import path, include
from .views import index

urlpatterns = [
    path('', index),
    path('admin/', admin.site.urls),
    path('api/', include('behavior.urls')),
    path('api/', include('anomaly.urls')),
]
