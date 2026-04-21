package com.almoullim.background_location

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding


class BackgroundLocationPlugin : FlutterPlugin, ActivityAware {

    private var activityBinding: ActivityPluginBinding? = null

    companion object {

        const val TAG = "com.almoullim.Log.Tag"
        const val PLUGIN_ID = "com.almoullim.background_location"
    }


    override fun onAttachedToEngine(binding: FlutterPluginBinding) {
        BackgroundLocationService.getInstance().onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        BackgroundLocationService.getInstance().onDetachedFromEngine()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        val service = BackgroundLocationService.getInstance()
        service.setActivity(binding)
        binding.addRequestPermissionsResultListener(service)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        val service = BackgroundLocationService.getInstance()
        activityBinding?.removeRequestPermissionsResultListener(service)
        activityBinding = null
        service.setActivity(null)
    }

}
