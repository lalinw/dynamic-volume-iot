# DyVo (Dynamic Volume) IoT ver.
Android app that adjusts volume of the target device based on indoor distance relative to the reference point using Round-Trip Time (802.11mc) on Fine Time Measurement enabled devices (IoT version) 

## Features
Android app makes RTT ranging requests and sends it as MQTT message to AWS IoT Core. Python script subscribes to the distance and scales it to a volume to update the master volume level on Windows PC. Android app subscribes to the volume from PC for display. Android device is authorized with AWS Cognito and PC is authorized via IoT certificates. 

## Requirements
- Android API Level 28+ ([Wifi RTT](https://developer.android.com/guide/topics/connectivity/wifi-rtt) enabled)
- Android device & Router/Access point supports 802.11mc
- Python installed computer

## How to use
1. Replace AWS endpoints and certificates with your own endpoints and certificates on the android application source code
2. Add `pem.crt`, `private.pem.key` and `public.pem.key` files to the folder `python_script/iot_certificates` 
3. Install application on an android device that supports IEEE 802.11mc
4. Run file `main.py` in folder `python_script` on the target device of volume adjustment

## Libraries, SDKs
- [PyCaw](https://github.com/AndreMiras/pycaw)
- [AWS Mobile Android SDK](https://docs.amplify.aws/sdk/pubsub/working-api/q/platform/android)
- [AWS IoT SDK](https://docs.aws.amazon.com/iot/latest/developerguide/iot-sdks.html)

### AWS technologies (Free tier compatible)
- AWS IoT Core 
- AWS Cognito
