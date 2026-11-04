/* libkotomo_mozc.so exports only its JNI entry points; Mozc, protobuf, abseil
   and the C++ runtime stay local so they cannot clash with other native
   libraries in the app (PLAN.md §4.2). */
{
  global:
    Java_com_yuanzhe_kotomo_engine_Mozc_*;
  local:
    *;
};
