package com.dexlike.launcher;

interface ITaskService {
    String snapshot(int displayId, int userId);
    String restore(int taskId, int displayId, int userId, int left, int top, int right, int bottom);
    String fitWindows(int displayId, int userId, int left, int top, int right, int bottom);
    void destroy() = 16777114;
}
