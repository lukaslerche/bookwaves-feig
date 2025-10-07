#!/bin/bash

for word in $(ls -l lib*.so* | grep '^-')
do
  if [ "${word:0:3}" == "lib" ]; then
    libfile=$word
    libminor=${libfile%.[0-9]*}
    libmajor=${libminor%.[0-9]*}
    libname=${libmajor%.[0-9]*}
    echo "$libname ==> $libmajor ==> $libfile"

    ln -sf ${libfile}  ${libmajor}
    ln -sf ${libmajor} ${libname}
  fi
done