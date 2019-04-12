#!/bin/bash
targetName=$1
loopRound=0
function wait_for_emulator {
   $ANDROID_HOME/platform-tools/adb wait-for-device
   until [[ ! $(($ANDROID_HOME/platform-tools/adb shell exit) 3>&1) == error* ]] && [[ $($ANDROID_HOME/platform-tools/adb -e shell getprop init.svc.bootanim) == stopped* ]]; do
      echo "device not yet ready"
      sleep 10
      loopRound=$((loopRound+1))
      if [ "$loopRound" -gt 80 ]
      then
         echo "Emulator not started" 1>&2
         exit 1
         break
      fi
   done
   echo "Device ready"
}

mkdir avd
yes "" | $ANDROID_HOME/tools/bin/avdmanager --verbose create avd -f -n buildavd -k "system-images;$targetName;default;x86_64"
nice -n 19 $ANDROID_HOME/tools/emulator -avd buildavd -no-audio -no-window -wipe-data &

$ANDROID_HOME/platform-tools/adb kill-server
$ANDROID_HOME/platform-tools/adb start-server
$ANDROID_HOME/platform-tools/adb reconnect
wait_for_emulator
