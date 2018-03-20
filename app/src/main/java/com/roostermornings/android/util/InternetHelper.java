package com.roostermornings.android.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.lang.reflect.Method;

public class InternetHelper {

        public static boolean noInternetConnection(Context context) {
            ConnectivityManager connectivityManager
                    = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if(connectivityManager == null) return true;
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();

            return activeNetworkInfo == null || !activeNetworkInfo.isConnected();
        }

        @SuppressWarnings("ConstantConditions")
        public static boolean mobileDataConnection(Context context) {
            boolean mobileDataEnabled = false; // Assume disabled

            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            try {
                //noinspection ConstantConditions
                Class cmClass = Class.forName(cm.getClass().getName());
                Method method = cmClass.getDeclaredMethod("getMobileDataEnabled");
                method.setAccessible(true); // Make the method callable
                // get the setting for "mobile data"
                mobileDataEnabled = (Boolean)method.invoke(cm);
            } catch (Exception e) {
                // Some problem accessible private API
                // TODO do whatever error handling you want here
            }

            return mobileDataEnabled;
        }
}
