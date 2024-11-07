#!/bin/bash
echo $(date +'%y-%b-%d %H:%M:%S.%3N') INFO Starting Clojure process...
set -x
exec java $JAVA_OPTS -cp 'deps-jars/*:src:res' -Dclojure.main.report=stderr clojure.main -m flamebin.main
