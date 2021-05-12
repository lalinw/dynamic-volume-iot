# Irene Wachirawutthichai
# CSS 532, Winter 2021


from __future__ import print_function
from awscrt import io, mqtt, auth, http
from awsiot import mqtt_connection_builder
import time as t
import json
from ctypes import cast, POINTER
from comtypes import CLSCTX_ALL
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

# Define ENDPOINT, CLIENT_ID, PATH_TO_CERT, PATH_TO_KEY, PATH_TO_ROOT, MESSAGE, TOPIC, and RANGE
ENDPOINT = "a1mf7ud1h3g6uv-ats.iot.us-east-2.amazonaws.com"
CLIENT_ID = "desktop"
PATH_TO_CERT = "iot_certificates/8b9e883a33-certificate.pem.crt"
PATH_TO_KEY = "iot_certificates/8b9e883a33-private.pem.key"
PATH_TO_ROOT = "iot_certificates/root.pem"
TOPIC = "adaptivev/active/volume"
DEVICE_NAME = "desktop"

# Default volume settings variable
MIN_DISTANCE, MAX_DISTANCE, MIN_VOLUME, MAX_VOLUME = 0, 5000, 10, 100


def position_to_volume(position):
    if position > MAX_DISTANCE:
        position = MAX_DISTANCE
    if position < MIN_DISTANCE:
        position = MIN_DISTANCE
    distance_range = MAX_DISTANCE - MIN_DISTANCE
    volume_range = MAX_VOLUME - MIN_VOLUME
    position_ratio = (position - MIN_DISTANCE) / distance_range
    return (position_ratio * volume_range) + MIN_VOLUME


def on_message_received(topic, payload, **kwargs):
    if topic == "adaptivev/settings":
        payload = json.loads(payload)
        global MIN_DISTANCE
        global MAX_DISTANCE
        global MIN_VOLUME
        global MAX_VOLUME
        MIN_DISTANCE = payload["minDistance"]
        MAX_DISTANCE = payload["maxDistance"]
        MIN_VOLUME = payload["minVolume"]
        MAX_VOLUME = payload["maxVolume"]
        print(MIN_DISTANCE)
        print(MAX_DISTANCE)
        print(MIN_VOLUME)
        print(MAX_VOLUME)
    elif topic == "adaptivev/active/distanceSD":
        print("distance SD ----- " + str(int(payload)))
    else:
        distance = int(payload)
        volumeToSet = int(position_to_volume(distance))
        set_volume_percentage(volumeToSet)
        distVolPair = {
            "type": "record",
            "distance": distance,
            "volume": volumeToSet
        }
        mqtt_connection.publish(
            topic="adaptivev/active",
            payload=json.dumps(distVolPair),
            qos=mqtt.QoS.AT_LEAST_ONCE)
        #print("♥♥♥ VOLUME >>> " + str(round(volume.GetMasterVolumeLevelScalar() * 100)))
        print("♦♦♦ DISTANCE >>> " + str(distance))


# Subscribe
def subscribe(topic):
    subscribe_future, packet_id = mqtt_connection.subscribe(
        topic=topic,
        qos=mqtt.QoS.AT_LEAST_ONCE,
        callback=on_message_received
    )
    subscribe_result = subscribe_future.result()


# Publish message to server desired number of times.
def publish(topic):
    print('Begin Publish...')
    while True:
        mqtt_connection.publish(
            topic=TOPIC,
            payload=str(round(volume.GetMasterVolumeLevelScalar() * 100)),
            qos=mqtt.QoS.AT_LEAST_ONCE)
        t.sleep(0.5)


def set_volume_percentage(x):
    volume.SetMasterVolumeLevelScalar(x / 100, None)


# MAIN METHOD
if __name__ == '__main__':
    # Initialization of the SOUND module with PyCaw
    devices = AudioUtilities.GetSpeakers()
    interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    volume = cast(interface, POINTER(IAudioEndpointVolume))

    # Spin up resources
    event_loop_group = io.EventLoopGroup(1)
    host_resolver = io.DefaultHostResolver(event_loop_group)
    client_bootstrap = io.ClientBootstrap(event_loop_group, host_resolver)
    mqtt_connection = mqtt_connection_builder.mtls_from_path(
        endpoint=ENDPOINT,
        cert_filepath=PATH_TO_CERT,
        pri_key_filepath=PATH_TO_KEY,
        client_bootstrap=client_bootstrap,
        ca_filepath=PATH_TO_ROOT,
        client_id=CLIENT_ID,
        clean_session=False,
        keep_alive_secs=6
    )
    print("Connecting to {} with client ID '{}'...".format(
        ENDPOINT, CLIENT_ID))

    # Make the connect() call
    connect_future = mqtt_connection.connect()

    # Future.result() waits until a result is available
    connect_future.result()
    print("Connected!")

    subscribe("adaptivev/settings")
    subscribe("adaptivev/active/distance")
    subscribe("adaptivev/active/distanceSD")

    publish("adaptivev/active/volume")

    # Wait for input from AWS IoT Console
    input()

    # Disconnect MQTT connection
    disconnect_future = mqtt_connection.disconnect()
    disconnect_future.result()
