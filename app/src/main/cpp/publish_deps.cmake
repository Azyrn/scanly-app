# cmake -DSRC=<installed deps> -DDEST=<cache entry> -P publish_deps.cmake
if(EXISTS ${DEST})
    return()
endif()
string(RANDOM LENGTH 8 suffix)
set(tmp ${DEST}.tmp-${suffix})
# Only what scanlypdf links and includes; qpdf's own command-line tools would double the size.
foreach(dep qpdf jpeg)
    file(COPY ${SRC}/${dep}/include DESTINATION ${tmp}/${dep})
    file(COPY ${SRC}/${dep}/lib/lib${dep}.a DESTINATION ${tmp}/${dep}/lib)
endforeach()
# Not NO_REPLACE: CMake cannot do that for directories. A plain rename onto a directory that
# another build already published fails (not empty), and that copy is then simply dropped.
file(RENAME ${tmp} ${DEST} RESULT renamed)
if(NOT renamed EQUAL 0)
    file(REMOVE_RECURSE ${tmp})
endif()
