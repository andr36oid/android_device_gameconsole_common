#!/bin/bash
cd ../../../../vendor/lineage || exit 1
git reset --hard
git clean -f -d

cd ../../art || exit 1
git reset --hard
git clean -f -d

cd ../bionic || exit 1
git reset --hard
git clean -f -d

cd ../build/soong || exit 1
git reset --hard

cd ../../external/compiler-rt || exit 1
git reset --hard
cd ../libdrm || exit 1
git reset --hard
git clean -f -d

cd ../../frameworks/av || exit 1
git reset --hard
git clean -f -d
cd ../base || exit 1
git reset --hard
git clean -f -d
cd ../native || exit 1
git reset --hard
git clean -f -d
cd ../opt/net/wifi/ || exit 1
git reset --hard
git clean -f -d

cd ../../../../hardware/interfaces || exit 1
git reset --hard
git clean -f -d
cd ../libhardware || exit 1
git reset --hard
git clean -f -d

cd ../../system/core || exit 1
git reset --hard
git clean -f -d
cd healthd || exit 1
git reset --hard
git clean -f -d
cd ../../hardware/interfaces || exit 1
git reset --hard
git clean -f -d
cd ../../memory/libion || exit 1
git reset --hard
git clean -f -d

cd ../../../build/make || exit 1
git reset --hard 
git clean -f -d

cd ../../packages/apps/Settings || exit 1
git reset --hard
git clean -f -d

cd ../../../lineage-sdk/ || exit 1
git reset --hard
git clean -f -d

cd ../device/gameconsole/common/patches || exit 1
