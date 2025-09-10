package com.transistorsoft.flutter.backgroundfetch;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class BackgroundFetchPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    private static final String TAG = "BackgroundFetch";
    private static final String PLUGIN_ID = "com.transistorsoft/flutter_background_fetch";
    private static final String METHOD_CHANNEL_NAME = "transistorsoft_flutter_background_fetch";
    private static final String EVENT_CHANNEL_NAME = "transistorsoft_flutter_background_fetch/events";

    private Context context;
    private Activity activity;
    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private EventSink eventSink;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        
        methodChannel = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL_NAME);
        methodChannel.setMethodCallHandler(this);
        
        eventChannel = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL_NAME);
        eventChannel.setStreamHandler(new StreamHandler() {
            @Override
            public void onListen(Object args, final EventSink eventSink) {
                BackgroundFetchPlugin.this.eventSink = eventSink;
            }
            @Override
            public void onCancel(Object args) {
                BackgroundFetchPlugin.this.eventSink = null;
            }
        });
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        methodChannel.setMethodCallHandler(null);
        methodChannel = null;
        eventChannel.setStreamHandler(null);
        eventChannel = null;
        context = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        this.activity = null;
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        this.activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        this.activity = binding.getActivity();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (call.method.equals("configure")) {
            result.success(0);  // Return STATUS_OK
        } else if (call.method.equals("start")) {
            result.success(true);
        } else if (call.method.equals("stop")) {
            result.success(true);
        } else if (call.method.equals("finish")) {
            result.success(true);
        } else if (call.method.equals("status")) {
            result.success(0);  // Return STATUS_OK
        } else {
            result.notImplemented();
        }
    }
}
