cmake_minimum_required(VERSION 3.22.1)
project(chiroru LANGUAGES C)

add_library(chiroru SHARED
    native_bridge.c runtime_status.c backend_registry.c
    backends/mali_jm_probe.c backends/chiron_5000001307/root.c
    reclaim_scan.c process_wait.c process_console.c)
target_include_directories(chiroru PRIVATE . jm)
target_compile_options(chiroru PRIVATE -O2)
target_link_libraries(chiroru PRIVATE log)

# Android installs/extracts lib*.so under the executable, read-only nativeLibDir.
add_executable(chiroru_console console_runner.c)
set_target_properties(chiroru_console PROPERTIES OUTPUT_NAME "libchiroru_console.so")
target_compile_options(chiroru_console PRIVATE -O2 -Wall -Wextra -Werror)

get_filename_component(CHIRORU_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.." ABSOLUTE)
if(NOT EXISTS "${CHIRORU_ROOT}/third_party/kernelsu/kernel/selinux/policy_writer.c")
    message(FATAL_ERROR "Initialize KernelSU: git submodule update --init --recursive")
endif()
set(POLICY_COMPAT "${CMAKE_CURRENT_BINARY_DIR}/policy-compat")
execute_process(COMMAND python3 "${CHIRORU_ROOT}/tools/gen_policy_writer.py"
    "${CHIRORU_ROOT}/linux-txn/source" "${CHIRORU_ROOT}/third_party/kernelsu/kernel/selinux" --check
    COMMAND_ERROR_IS_FATAL ANY)
execute_process(COMMAND python3 "${CHIRORU_ROOT}/tools/policy_host/build.py"
    --prepare-only --out "${POLICY_COMPAT}" COMMAND_ERROR_IS_FATAL ANY)
set(VENDOR_POLICY "${CHIRORU_ROOT}/linux-txn/source/security/selinux")
set(VERIFIED_POLICY "${CHIRORU_ROOT}/third_party/kernelsu/kernel")
add_library(console_policy OBJECT
    ${VENDOR_POLICY}/ss/policydb.c ${VENDOR_POLICY}/ss/ebitmap.c
    ${VENDOR_POLICY}/ss/avtab.c ${VENDOR_POLICY}/ss/hashtab.c
    ${VENDOR_POLICY}/ss/symtab.c ${VENDOR_POLICY}/ss/conditional.c
    ${POLICY_COMPAT}/mls_validate.c ${VERIFIED_POLICY}/selinux/sepolicy.c
    ${VERIFIED_POLICY}/selinux/policy_writer.c ${CHIRORU_ROOT}/root-console/policy.c)
target_include_directories(console_policy PRIVATE "${POLICY_COMPAT}"
    "${CHIRORU_ROOT}/tools/policy_host" "${VENDOR_POLICY}" "${VENDOR_POLICY}/ss"
    "${VERIFIED_POLICY}" "${VERIFIED_POLICY}/include" "${VERIFIED_POLICY}/selinux"
    "${CHIRORU_ROOT}/third_party/kernelsu")
target_compile_options(console_policy PRIVATE -O2 -ffunction-sections -fdata-sections
    "SHELL:-include ${CHIRORU_ROOT}/tools/policy_host/compat.h"
    "SHELL:-include ${VERIFIED_POLICY}/selinux/chiron_context.h")
add_executable(chiroru_root_console "${CHIRORU_ROOT}/root-console/server.c"
    console_runner.c $<TARGET_OBJECTS:console_policy>)
target_compile_definitions(chiroru_root_console PRIVATE CONSOLE_LIBRARY)
target_compile_options(chiroru_root_console PRIVATE -O2 -Wall -Wextra -Werror)
target_link_options(chiroru_root_console PRIVATE -Wl,--gc-sections)
set_target_properties(chiroru_root_console PROPERTIES OUTPUT_NAME "libchiroru_root_console.so")
