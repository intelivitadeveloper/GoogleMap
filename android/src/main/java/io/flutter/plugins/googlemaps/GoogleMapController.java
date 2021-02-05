// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.googlemaps;

import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.CREATED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.DESTROYED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.PAUSED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.RESUMED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.STARTED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.STOPPED;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.LatLngBounds.Builder;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONArray;
import org.json.JSONException;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.platform.PlatformView;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controller of a single GoogleMaps MapView instance.
 */
final class GoogleMapController
        implements Application.ActivityLifecycleCallbacks,
        GoogleMap.OnCameraIdleListener,
        GoogleMap.OnCameraMoveListener,
        GoogleMap.OnCameraMoveStartedListener,
        GoogleMap.OnInfoWindowClickListener,
        GoogleMap.OnMarkerClickListener,
        GoogleMap.OnPolygonClickListener,
        GoogleMap.OnPolylineClickListener,
        GoogleMap.OnCircleClickListener,
        GoogleMapOptionsSink,
        MethodChannel.MethodCallHandler,
        OnMapReadyCallback,
        GoogleMap.OnMapClickListener,
        GoogleMap.OnMapLongClickListener,
        GoogleMap.OnMarkerDragListener,
        PlatformView {

    private static final String TAG = "GoogleMapController";
    private final int id;
    private final AtomicInteger activityState;
    private final MethodChannel methodChannel;
    private final PluginRegistry.Registrar registrar;
    private final MapView mapView;
    private GoogleMap googleMap;
    private boolean trackCameraPosition = false;
    private boolean myLocationEnabled = false;
    private boolean myLocationButtonEnabled = false;
    private boolean indoorEnabled = true;
    private boolean trafficEnabled = false;
    private boolean disposed = false;
    private final float density;
    private MethodChannel.Result mapReadyResult;
    private final int registrarActivityHashCode;
    private final Context context;
    private final MarkersController markersController;
    private final PolygonsController polygonsController;
    private final PolylinesController polylinesController;
    private final CirclesController circlesController;
    private List<Object> initialMarkers;
    private List<Object> initialPolygons;
    private List<Object> initialPolylines;
    private List<Object> initialCircles;
    GroundOverlay groundOverlay;
    DownloadOverlay downloadOverlay;
    private boolean isMoveFromCamera = false;
    String deviceId = "";
    String overLayResolution = "";
    String pollutionType = "";
    Bitmap overlayImage;
    String fastRoute = "";
    String cleanRoute = "";
    String tempRoute = "";

    boolean needToLoadOverlay = true;

    Polyline fastRoutePolyline;
    Polyline cleanRoutePolyline;
    Polyline tempRoutePolyline;

    GoogleMapController(
            int id,
            Context context,
            AtomicInteger activityState,
            PluginRegistry.Registrar registrar,
            GoogleMapOptions options) {
        this.id = id;
        this.context = context;
        this.activityState = activityState;
        this.registrar = registrar;
        this.mapView = new MapView(context, options);
        this.density = context.getResources().getDisplayMetrics().density;
        methodChannel =
                new MethodChannel(registrar.messenger(), "plugins.flutter.io/google_maps_" + id);
        methodChannel.setMethodCallHandler(this);
        this.registrarActivityHashCode = registrar.activity().hashCode();
        this.markersController = new MarkersController(methodChannel);
        this.polygonsController = new PolygonsController(methodChannel);
        this.polylinesController = new PolylinesController(methodChannel, density);
        this.circlesController = new CirclesController(methodChannel);
    }

    @Override
    public View getView() {
        return mapView;
    }

    void init() {
        switch (activityState.get()) {
            case STOPPED:
                mapView.onCreate(null);
                mapView.onStart();
                mapView.onResume();
                mapView.onPause();
                mapView.onStop();
                break;
            case PAUSED:
                mapView.onCreate(null);
                mapView.onStart();
                mapView.onResume();
                mapView.onPause();
                break;
            case RESUMED:
                mapView.onCreate(null);
                mapView.onStart();
                mapView.onResume();
                break;
            case STARTED:
                mapView.onCreate(null);
                mapView.onStart();
                break;
            case CREATED:
                mapView.onCreate(null);
                break;
            case DESTROYED:
                // Nothing to do, the activity has been completely destroyed.
                break;
            default:
                throw new IllegalArgumentException(
                        "Cannot interpret " + activityState.get() + " as an activity state");
        }
        registrar.activity().getApplication().registerActivityLifecycleCallbacks(this);
        mapView.getMapAsync(this);
    }

    private void moveCamera(CameraUpdate cameraUpdate) {
        googleMap.moveCamera(cameraUpdate);
    }

    private void animateCamera(CameraUpdate cameraUpdate) {
        googleMap.animateCamera(cameraUpdate);
    }

    private CameraPosition getCameraPosition() {
        return trackCameraPosition ? googleMap.getCameraPosition() : null;
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.googleMap = googleMap;
        this.googleMap.setIndoorEnabled(this.indoorEnabled);
        this.googleMap.setTrafficEnabled(this.trafficEnabled);
        googleMap.setOnInfoWindowClickListener(this);
        if (mapReadyResult != null) {
            mapReadyResult.success(null);
            mapReadyResult = null;
        }
        googleMap.setOnCameraMoveStartedListener(this);
        googleMap.setOnCameraMoveListener(this);
        googleMap.setOnCameraIdleListener(this);
        googleMap.setOnMarkerClickListener(this);
        googleMap.setOnMarkerDragListener(this);
        googleMap.setOnPolygonClickListener(this);
        googleMap.setOnPolylineClickListener(this);
        googleMap.setOnCircleClickListener(this);
        googleMap.setOnMapClickListener(this);
        googleMap.setOnMapLongClickListener(this);
        updateMyLocationSettings();
        markersController.setGoogleMap(googleMap);
        polygonsController.setGoogleMap(googleMap);
        polylinesController.setGoogleMap(googleMap);
        circlesController.setGoogleMap(googleMap);
        updateInitialMarkers();
        updateInitialPolygons();
        updateInitialPolylines();
        updateInitialCircles();
    }

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        switch (call.method) {
            case "map#settingDidChange":
                Log.e("Settingchanged", "Settingchanged");
                HashMap map = (HashMap) call.arguments;
                Log.e("HashMapValue", "" + map.toString());
                overLayResolution = (String) map.get("overlayResulationValue");
                deviceId = (String) map.get("authorizedUserDeviceId");
                pollutionType = (String) map.get("overlayTypeValue");
                if (pollutionType.equalsIgnoreCase("0"))
                    pollutionType = "REL";
                else
                    pollutionType = "ABS";

                break;
            case "map#setPolyLine":
                isMoveFromCamera = false;
                Log.e("setPolyLine", "setPolyLine");
                HashMap map1 = (HashMap) call.arguments;
                Log.e("HashMapValue", "" + map1.toString());
                String isNewRequest = "" + map1.get("isNewRequest");
                String routeType = (String) map1.get("type");
                String points = (String) map1.get("points");
                if (isNewRequest.equalsIgnoreCase("true")) {
                    googleMap.clear();
                    fastRoute = "";
                    cleanRoute = "";
                    tempRoute = "";
                    needToLoadOverlay = false;
                } else if (routeType != null && routeType.equalsIgnoreCase("4")) {
                    tempRoute = "";
                    googleMap.clear();
                    if (downloadOverlay != null)
                        downloadOverlay.cancel(true);
                    downloadOverlay = new DownloadOverlay();
                    downloadOverlay.execute();
                    needToLoadOverlay = true;
                    return;
                } else if (routeType != null && (routeType.equalsIgnoreCase("5") || routeType.equalsIgnoreCase("6"))) {
                    Log.e("RouteCleaned", "RouteCleaned");
                    tempRoute = "";
                    fastRoute = "";
                    cleanRoute = "";
                    if (fastRoutePolyline != null)
                        fastRoutePolyline.remove();
                    if (cleanRoutePolyline != null)
                        cleanRoutePolyline.remove();
                    if (tempRoutePolyline != null)
                        tempRoutePolyline.remove();
                }

                if (routeType != null && (routeType.equalsIgnoreCase("1") || routeType.equalsIgnoreCase("5"))) {
                    Log.e("FastRoute", "FastRoute");
                    fastRoute = points;
                } else if (routeType != null && (routeType.equalsIgnoreCase("2") || routeType.equalsIgnoreCase("6"))) {
                    Log.e("CleanRoute", "CleanRoute");
                    cleanRoute = points;
                } else {
                    tempRoute = points;
                }

                if (fastRoute != null && !fastRoute.isEmpty()) {
                    addPolyline(1, fastRoute, true);
                }
                if (cleanRoute != null && !cleanRoute.isEmpty()) {
                    addPolyline(2, cleanRoute, true);
                }
                if (tempRoute != null && !tempRoute.isEmpty()) {
                    addPolyline(3, tempRoute, true);
                }
                break;
            case "map#waitForMap":
                if (googleMap != null) {
                    result.success(null);
                    return;
                }
                mapReadyResult = result;
                break;
            case "map#update": {
                Convert.interpretGoogleMapOptions(call.argument("options"), this);
                result.success(Convert.cameraPositionToJson(getCameraPosition()));
                break;
            }
            case "map#getVisibleRegion": {
                if (googleMap != null) {
                    LatLngBounds latLngBounds = googleMap.getProjection().getVisibleRegion().latLngBounds;
                    result.success(Convert.latlngBoundsToJson(latLngBounds));
                } else {
                    result.error(
                            "GoogleMap uninitialized",
                            "getVisibleRegion called prior to map initialization",
                            null);
                }
                break;
            }
            case "map#getScreenCoordinate": {
                if (googleMap != null) {
                    LatLng latLng = Convert.toLatLng(call.arguments);
                    Point screenLocation = googleMap.getProjection().toScreenLocation(latLng);
                    result.success(Convert.pointToJson(screenLocation));
                } else {
                    result.error(
                            "GoogleMap uninitialized",
                            "getScreenCoordinate called prior to map initialization",
                            null);
                }
                break;
            }
            case "map#getLatLng": {
                if (googleMap != null) {
                    Point point = Convert.toPoint(call.arguments);
                    LatLng latLng = googleMap.getProjection().fromScreenLocation(point);
                    result.success(Convert.latLngToJson(latLng));
                } else {
                    result.error(
                            "GoogleMap uninitialized", "getLatLng called prior to map initialization", null);
                }
                break;
            }
            case "camera#move": {
                final CameraUpdate cameraUpdate =
                        Convert.toCameraUpdate(call.argument("cameraUpdate"), density);
                moveCamera(cameraUpdate);
                result.success(null);
                break;
            }
            case "camera#animate": {
                final CameraUpdate cameraUpdate =
                        Convert.toCameraUpdate(call.argument("cameraUpdate"), density);
                animateCamera(cameraUpdate);
                result.success(null);
                break;
            }
            case "markers#update": {
                Object markersToAdd = call.argument("markersToAdd");
                markersController.addMarkers((List<Object>) markersToAdd);
                Object markersToChange = call.argument("markersToChange");
                markersController.changeMarkers((List<Object>) markersToChange);
                Object markerIdsToRemove = call.argument("markerIdsToRemove");
                markersController.removeMarkers((List<Object>) markerIdsToRemove);
                result.success(null);
                break;
            }
            case "polygons#update": {
                Object polygonsToAdd = call.argument("polygonsToAdd");
                polygonsController.addPolygons((List<Object>) polygonsToAdd);
                Object polygonsToChange = call.argument("polygonsToChange");
                polygonsController.changePolygons((List<Object>) polygonsToChange);
                Object polygonIdsToRemove = call.argument("polygonIdsToRemove");
                polygonsController.removePolygons((List<Object>) polygonIdsToRemove);
                result.success(null);
                break;
            }
            case "polygons#settingDidChange": {
                Object polygonsToAdd = call.argument("polygonsToAdd");
                polygonsController.addPolygons((List<Object>) polygonsToAdd);
                Object polygonsToChange = call.argument("polygonsToChange");
                polygonsController.changePolygons((List<Object>) polygonsToChange);
                Object polygonIdsToRemove = call.argument("polygonIdsToRemove");
                polygonsController.removePolygons((List<Object>) polygonIdsToRemove);
                result.success(null);
                break;
            }
            case "polylines#update": {
                Object polylinesToAdd = call.argument("polylinesToAdd");
                polylinesController.addPolylines((List<Object>) polylinesToAdd);
                Object polylinesToChange = call.argument("polylinesToChange");
                polylinesController.changePolylines((List<Object>) polylinesToChange);
                Object polylineIdsToRemove = call.argument("polylineIdsToRemove");
                polylinesController.removePolylines((List<Object>) polylineIdsToRemove);
                result.success(null);
                break;
            }
            case "circles#update": {
                Object circlesToAdd = call.argument("circlesToAdd");
                circlesController.addCircles((List<Object>) circlesToAdd);
                Object circlesToChange = call.argument("circlesToChange");
                circlesController.changeCircles((List<Object>) circlesToChange);
                Object circleIdsToRemove = call.argument("circleIdsToRemove");
                circlesController.removeCircles((List<Object>) circleIdsToRemove);
                result.success(null);
                break;
            }
            case "map#isCompassEnabled": {
                result.success(googleMap.getUiSettings().isCompassEnabled());
                break;
            }
            case "map#isMapToolbarEnabled": {
                result.success(googleMap.getUiSettings().isMapToolbarEnabled());
                break;
            }
            case "map#getMinMaxZoomLevels": {
                List<Float> zoomLevels = new ArrayList<>(2);
                zoomLevels.add(googleMap.getMinZoomLevel());
                zoomLevels.add(googleMap.getMaxZoomLevel());
                result.success(zoomLevels);
                break;
            }
            case "map#isZoomGesturesEnabled": {
                result.success(googleMap.getUiSettings().isZoomGesturesEnabled());
                break;
            }
            case "map#isScrollGesturesEnabled": {
                result.success(googleMap.getUiSettings().isScrollGesturesEnabled());
                break;
            }
            case "map#isTiltGesturesEnabled": {
                result.success(googleMap.getUiSettings().isTiltGesturesEnabled());
                break;
            }
            case "map#isRotateGesturesEnabled": {
                result.success(googleMap.getUiSettings().isRotateGesturesEnabled());
                break;
            }
            case "map#isMyLocationButtonEnabled": {
                result.success(googleMap.getUiSettings().isMyLocationButtonEnabled());
                break;
            }
            case "map#isTrafficEnabled": {
                result.success(googleMap.isTrafficEnabled());
                break;
            }
            case "map#setStyle": {
                String mapStyle = (String) call.arguments;
                boolean mapStyleSet;
                if (mapStyle == null) {
                    mapStyleSet = googleMap.setMapStyle(null);
                } else {
                    mapStyleSet = googleMap.setMapStyle(new MapStyleOptions(mapStyle));
                }
                ArrayList<Object> mapStyleResult = new ArrayList<>(2);
                mapStyleResult.add(mapStyleSet);
                if (!mapStyleSet) {
                    mapStyleResult.add(
                            "Unable to set the map style. Please check console logs for errors.");
                }
                result.success(mapStyleResult);
                break;
            }
            default:
                result.notImplemented();
        }
    }

    @Override
    public void onMapClick(LatLng latLng) {
        final Map<String, Object> arguments = new HashMap<>(2);
        arguments.put("position", Convert.latLngToJson(latLng));
        methodChannel.invokeMethod("map#onTap", arguments);
    }

    @Override
    public void onMapLongClick(LatLng latLng) {
        final Map<String, Object> arguments = new HashMap<>(2);
        arguments.put("position", Convert.latLngToJson(latLng));
        methodChannel.invokeMethod("map#onLongPress", arguments);
    }

    @Override
    public void onCameraMoveStarted(int reason) {
        final Map<String, Object> arguments = new HashMap<>(2);
        boolean isGesture = reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE;
        arguments.put("isGesture", isGesture);
        methodChannel.invokeMethod("camera#onMoveStarted", arguments);
    }

    @Override
    public void onInfoWindowClick(Marker marker) {
        markersController.onInfoWindowTap(marker.getId());
    }

    @Override
    public void onCameraMove() {
        if (!trackCameraPosition) {
            return;
        }
        final Map<String, Object> arguments = new HashMap<>(2);
        arguments.put("position", Convert.cameraPositionToJson(googleMap.getCameraPosition()));
        methodChannel.invokeMethod("camera#onMove", arguments);
        isMoveFromCamera = true;
        Log.e("CameraMoved", "cameraMoved");
        if (needToLoadOverlay) {
            if (downloadOverlay != null)
                downloadOverlay.cancel(true);

            downloadOverlay = new DownloadOverlay();
            downloadOverlay.execute();
        }

    }

    @Override
    public void onCameraIdle() {
        methodChannel.invokeMethod("camera#onIdle", Collections.singletonMap("map", id));
    }

    @Override
    public boolean onMarkerClick(Marker marker) {
        return markersController.onMarkerTap(marker.getId());
    }

    @Override
    public void onMarkerDragStart(Marker marker) {
    }

    @Override
    public void onMarkerDrag(Marker marker) {
    }

    @Override
    public void onMarkerDragEnd(Marker marker) {
        markersController.onMarkerDragEnd(marker.getId(), marker.getPosition());
    }

    @Override
    public void onPolygonClick(Polygon polygon) {
        polygonsController.onPolygonTap(polygon.getId());
    }

    @Override
    public void onPolylineClick(Polyline polyline) {
        polylinesController.onPolylineTap(polyline.getId());
    }

    @Override
    public void onCircleClick(Circle circle) {
        circlesController.onCircleTap(circle.getId());
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        methodChannel.setMethodCallHandler(null);
        mapView.onDestroy();
        registrar.activity().getApplication().unregisterActivityLifecycleCallbacks(this);
    }

    // @Override
    // The minimum supported version of Flutter doesn't have this method on the PlatformView interface, but the maximum
    // does. This will override it when available even with the annotation commented out.
    public void onInputConnectionLocked() {
        // TODO(mklim): Remove this empty override once https://github.com/flutter/flutter/issues/40126 is fixed in stable.
    }

    ;

    // @Override
    // The minimum supported version of Flutter doesn't have this method on the PlatformView interface, but the maximum
    // does. This will override it when available even with the annotation commented out.
    public void onInputConnectionUnlocked() {
        // TODO(mklim): Remove this empty override once https://github.com/flutter/flutter/issues/40126 is fixed in stable.
    }

    ;

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) {
            return;
        }
        mapView.onCreate(savedInstanceState);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) {
            return;
        }
        mapView.onStart();
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) {
            return;
        }
        mapView.onResume();
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) {
            return;
        }
        mapView.onPause();
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) {
            return;
        }
        mapView.onStop();
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) {
            return;
        }
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) {
            return;
        }
        mapView.onDestroy();
    }

    // GoogleMapOptionsSink methods

    @Override
    public void setCameraTargetBounds(LatLngBounds bounds) {
        googleMap.setLatLngBoundsForCameraTarget(bounds);
    }

    @Override
    public void setCompassEnabled(boolean compassEnabled) {
        googleMap.getUiSettings().setCompassEnabled(compassEnabled);
    }

    @Override
    public void setMapToolbarEnabled(boolean mapToolbarEnabled) {
        googleMap.getUiSettings().setMapToolbarEnabled(mapToolbarEnabled);
    }

    @Override
    public void setMapType(int mapType) {
        googleMap.setMapType(mapType);
    }

    @Override
    public void setTrackCameraPosition(boolean trackCameraPosition) {
        this.trackCameraPosition = trackCameraPosition;
    }

    @Override
    public void setRotateGesturesEnabled(boolean rotateGesturesEnabled) {
        googleMap.getUiSettings().setRotateGesturesEnabled(rotateGesturesEnabled);
    }

    @Override
    public void setScrollGesturesEnabled(boolean scrollGesturesEnabled) {
        googleMap.getUiSettings().setScrollGesturesEnabled(scrollGesturesEnabled);
    }

    @Override
    public void setTiltGesturesEnabled(boolean tiltGesturesEnabled) {
        googleMap.getUiSettings().setTiltGesturesEnabled(tiltGesturesEnabled);
    }

    @Override
    public void setMinMaxZoomPreference(Float min, Float max) {
        googleMap.resetMinMaxZoomPreference();
        if (min != null) {
            googleMap.setMinZoomPreference(min);
        }
        if (max != null) {
            googleMap.setMaxZoomPreference(max);
        }
    }

    @Override
    public void setPadding(float top, float left, float bottom, float right) {
        if (googleMap != null) {
            googleMap.setPadding(
                    (int) (left * density),
                    (int) (top * density),
                    (int) (right * density),
                    (int) (bottom * density));
        }
    }

    @Override
    public void setZoomGesturesEnabled(boolean zoomGesturesEnabled) {
        googleMap.getUiSettings().setZoomGesturesEnabled(zoomGesturesEnabled);
    }

    @Override
    public void setMyLocationEnabled(boolean myLocationEnabled) {
        if (this.myLocationEnabled == myLocationEnabled) {
            return;
        }
        this.myLocationEnabled = myLocationEnabled;
        if (googleMap != null) {
            updateMyLocationSettings();
        }
    }

    @Override
    public void setMyLocationButtonEnabled(boolean myLocationButtonEnabled) {
        if (this.myLocationButtonEnabled == myLocationButtonEnabled) {
            return;
        }
        this.myLocationButtonEnabled = myLocationButtonEnabled;
        if (googleMap != null) {
            updateMyLocationSettings();
        }
    }

    @Override
    public void setInitialMarkers(Object initialMarkers) {
        this.initialMarkers = (List<Object>) initialMarkers;
        if (googleMap != null) {
            updateInitialMarkers();
        }
    }

    private void updateInitialMarkers() {
        markersController.addMarkers(initialMarkers);
    }

    @Override
    public void setInitialPolygons(Object initialPolygons) {
        this.initialPolygons = (List<Object>) initialPolygons;
        if (googleMap != null) {
            updateInitialPolygons();
        }
    }

    private void updateInitialPolygons() {
        polygonsController.addPolygons(initialPolygons);
    }

    @Override
    public void setInitialPolylines(Object initialPolylines) {
        this.initialPolylines = (List<Object>) initialPolylines;
        if (googleMap != null) {
            updateInitialPolylines();
        }
    }

    private void updateInitialPolylines() {
        polylinesController.addPolylines(initialPolylines);
    }

    @Override
    public void setInitialCircles(Object initialCircles) {
        this.initialCircles = (List<Object>) initialCircles;
        if (googleMap != null) {
            updateInitialCircles();
        }
    }

    private void updateInitialCircles() {
        circlesController.addCircles(initialCircles);
    }

    class DownloadOverlay extends AsyncTask<Void, Void, Bitmap> {
        double north, south, west, east;
        String url;

        DownloadOverlay() {

            LatLngBounds curScreen = googleMap.getProjection()
                    .getVisibleRegion().latLngBounds;
            north = curScreen.northeast.latitude;
            south = curScreen.southwest.latitude;
            west = curScreen.southwest.longitude;
            east = curScreen.northeast.longitude;

            url = "http://ec2-52-56-59-47.eu-west-2.compute.amazonaws.com/?meth=bounds&N=" +
                    north +
                    "&S=" +
                    south +
                    "&W=" +
                    west +
                    "&E=" +
                    east +
                    "&RAD=15&SIZE=50" +
                    "&DID=" +
                    deviceId +
                    "&RET=IMG";
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            if (bitmap == null) {
                String str = "ImageNull";
                Log.e(str, str);
                return;
            }
            overlayImage = bitmap;
            LatLngBounds latLngBounds = new LatLngBounds(new LatLng(south, west), new LatLng(north, east));
            addGroundOverlay(bitmap, latLngBounds);
            Log.e("OverlayDownloaded", "OverlayDownloaed");
            if (!isMoveFromCamera) {
                if (fastRoute != null && !fastRoute.isEmpty()) {
                    addPolyline(1, fastRoute, true);
                }
                if (cleanRoute != null && !cleanRoute.isEmpty()) {
                    addPolyline(2, cleanRoute, true);
                }
                if (tempRoute != null && !tempRoute.isEmpty()) {
                    addPolyline(3, tempRoute, true);
                }
            }
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

        }

        @Override
        protected Bitmap doInBackground(Void... voids) {
            try {
                return BitmapFactory.decodeStream(new URL(url).openConnection().getInputStream());
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void updateMyLocationSettings() {
        if (hasLocationPermission()) {
            // The plugin doesn't add the location permission by default so that apps that don't need
            // the feature won't require the permission.
            // Gradle is doing a static check for missing permission and in some configurations will
            // fail the build if the permission is missing. The following disables the Gradle lint.
            //noinspection ResourceType
            googleMap.setMyLocationEnabled(myLocationEnabled);
            googleMap.getUiSettings().setMyLocationButtonEnabled(myLocationButtonEnabled);
        } else {
            // TODO(amirh): Make the options update fail.
            // https://github.com/flutter/flutter/issues/24327
            Log.e(TAG, "Cannot enable MyLocation layer as location permissions are not granted");
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private int checkSelfPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }
        return context.checkPermission(
                permission, android.os.Process.myPid(), android.os.Process.myUid());
    }

    public boolean oddValue(int i) {
        return (i & 1) != 0;
    }

    public void setIndoorEnabled(boolean indoorEnabled) {
        this.indoorEnabled = indoorEnabled;
    }

    public void setTrafficEnabled(boolean trafficEnabled) {
        this.trafficEnabled = trafficEnabled;
    }

    private void addPolyline(int type, String str, boolean showMarker) {
        try {
            JSONArray jsonArray = new JSONArray(str);
            int cnt = jsonArray.length();
            int pos = 0;
            double lat;
            double lng;
            LatLng[] latLngs = new LatLng[cnt / 2];
            while (pos < cnt) {
                lat = jsonArray.getDouble(pos);
                lng = jsonArray.getDouble(pos + 1);
                latLngs[pos / 2] = new LatLng(lat, lng);
                pos += 2;
            }
            int color = type == 1 ? Color.RED : type == 2 ? Color.GREEN : Color.BLUE;
            if (latLngs.length != 0) {
                Log.e("StartPoint", "" + (new LatLng(latLngs[0].latitude, latLngs[0].longitude).toString()));
                Log.e("EndPoint", "" + (new LatLng(latLngs[latLngs.length - 1].latitude, latLngs[latLngs.length - 1].longitude)).toString());
                LatLng endLatlong = new LatLng(latLngs[latLngs.length - 1].latitude, latLngs[latLngs.length - 1].longitude);
                LatLng startLatLong = new LatLng(latLngs[0].latitude, latLngs[0].longitude);
                if (type == 1) {
                    fastRoutePolyline = googleMap.addPolyline(new PolylineOptions().add(latLngs).width(12).color(color));
                } else if (type == 2) {
                    cleanRoutePolyline = googleMap.addPolyline(new PolylineOptions().add(latLngs).width(12).color(color));
                } else {
                    tempRoutePolyline = googleMap.addPolyline(new PolylineOptions().add(latLngs).width(12).color(color));
                }


                Log.e("TypeToAddPolyline", "" + type);
                if (isMoveFromCamera)
                    return;
                LatLngBounds.Builder bounds = new LatLngBounds.Builder();
                bounds.include(endLatlong);
                bounds.include(startLatLong);

                googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 150));
                if (showMarker) {
                    MarkerOptions mo = new MarkerOptions().position(startLatLong).title("Start");
                    googleMap.addMarker(mo);
                    mo = new MarkerOptions().position(endLatlong).title("Finish");
                    googleMap.addMarker(mo);
                }

            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

    }

    private void addGroundOverlay(Bitmap bitmap, LatLngBounds bounds) {

        GroundOverlayOptions groundOverlayOptions = new
                GroundOverlayOptions();
        BitmapDescriptor bitmapDescriptor =
                BitmapDescriptorFactory.fromBitmap(bitmap);
        groundOverlayOptions.image(bitmapDescriptor);
        groundOverlayOptions.positionFromBounds(bounds);
        if (groundOverlay != null)
            groundOverlay.remove();
        groundOverlay = googleMap.addGroundOverlay(groundOverlayOptions);

    }
}
