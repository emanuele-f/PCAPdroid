project(common C)

ADD_LIBRARY(common STATIC uid_lru.c utils.c jni_utils.c uid_resolver.c)
