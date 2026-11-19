package com.dexlike.launcher;

import android.os.ParcelFileDescriptor;

interface ITaskService {
    String snapshot(int displayId, int userId) = 0;
    String restore(int taskId, int displayId, int userId, int left, int top, int right, int bottom) = 1;
    String fitWindows(int displayId, int userId, int left, int top, int right, int bottom) = 2;
    String minimize(int taskId, int displayId, int userId) = 3;
    String lowerDesktop(int taskId, int displayId, int userId) = 4;
    ParcelFileDescriptor wallpaper(int userId) = 5;
    String activate(String packageName, int displayId, int userId, int left, int top, int right, int bottom) = 6;
    void destroy() = 16777114;
}
