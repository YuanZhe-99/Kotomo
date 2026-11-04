# Host tests for the librime bridge (spike S2). The shared data directory is staged into the build
# tree by stage-data.cmake, the same script the Android build uses for its assets.

set(rime_data "${CMAKE_CURRENT_BINARY_DIR}/rime-data")
add_custom_target(kotomo_rime_data
  COMMAND "${CMAKE_COMMAND}" "-DKOTOMO_ROOT=${KOTOMO_ROOT}" "-DOUT=${rime_data}"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/../stage-data.cmake"
  COMMENT "Staging librime shared data")

add_executable(kotomo_rime_test rime_bridge_test.cc)
target_link_libraries(kotomo_rime_test PRIVATE kotomo_rime_bridge)
add_dependencies(kotomo_rime_test kotomo_rime_data)

# The test empties and reuses this user directory inside the build tree on every run.
add_test(NAME kotomo_rime_test
         COMMAND kotomo_rime_test "${rime_data}" "${CMAKE_CURRENT_BINARY_DIR}/rime-user")
