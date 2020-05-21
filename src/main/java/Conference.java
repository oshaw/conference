import com.github.sarxos.webcam.ds.buildin.natives.Device;
import com.github.sarxos.webcam.ds.buildin.natives.DeviceList;
import com.github.sarxos.webcam.ds.buildin.natives.OpenIMAJGrabber;
import org.bridj.Pointer;

public class Conference {
    public static void main(String[] arguments) {
        OpenIMAJGrabber openIMAJGrabber = new OpenIMAJGrabber();
        Pointer<DeviceList> pointerDeviceList = openIMAJGrabber.getVideoDevices();
        DeviceList deviceList = pointerDeviceList.get();
        Pointer<Device> pointerDevice = deviceList.getDevice(0);
        Device device = pointerDevice.get();
    }
}
