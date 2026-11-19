package com.dexlike.launcher;

interface ITaskService {
    String snapshot(int displayId, int userId) = 0;
    String restore(int taskId, int displayId, int userId, int left, int top, int right, int bottom) = 1;
    String fitWindows(int displayId, int userId, int left, int top, int right, int bottom) = 2;
    void destroy() = 16777114;
}
