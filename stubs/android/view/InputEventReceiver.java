package android.view;
import android.os.Looper;
public abstract class InputEventReceiver {
    public InputEventReceiver(InputChannel channel, Looper looper) {}
    public void onInputEvent(InputEvent event) {}
    public void finishInputEvent(InputEvent event, boolean handled) {}
    public void dispose() {}
}
