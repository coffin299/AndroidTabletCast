package com.androidtabletcast;

interface IInputInjector {
    void destroy() = 16777114;
    boolean injectTouch(int action, float x, float y, long downTime) = 2;
    boolean injectKey(int action, int keyCode, int meta, long downTime) = 3;
    boolean injectScroll(float x, float y, float delta) = 4;
}
