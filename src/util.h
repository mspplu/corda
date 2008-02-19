/* Copyright (c) 2008, Avian Contributors

   Permission to use, copy, modify, and/or distribute this software
   for any purpose with or without fee is hereby granted, provided
   that the above copyright notice and this permission notice appear
   in all copies.

   There is NO WARRANTY for this software.  See license.txt for
   details. */

#ifndef UTIL_H
#define UTIL_H

#include "machine.h"

namespace vm {

object
hashMapFindNode(Thread* t, object map, object key,
                uint32_t (*hash)(Thread*, object),
                bool (*equal)(Thread*, object, object));

inline object
hashMapFind(Thread* t, object map, object key,
            uint32_t (*hash)(Thread*, object),
            bool (*equal)(Thread*, object, object))
{
  object n = hashMapFindNode(t, map, key, hash, equal);
  return (n ? tripleSecond(t, n) : 0);
}

void
hashMapResize(Thread* t, object map, uint32_t (*hash)(Thread*, object),
              unsigned size);

void
hashMapInsert(Thread* t, object map, object key, object value,
              uint32_t (*hash)(Thread*, object));

inline bool
hashMapInsertOrReplace(Thread* t, object map, object key, object value,
                       uint32_t (*hash)(Thread*, object),
                       bool (*equal)(Thread*, object, object))
{
  object n = hashMapFindNode(t, map, key, hash, equal);
  if (n == 0) {
    hashMapInsert(t, map, key, value, hash);
    return true;
  } else {
    set(t, n, TripleSecond, value);
    return false;
  }
}

inline bool
hashMapInsertMaybe(Thread* t, object map, object key, object value,
                   uint32_t (*hash)(Thread*, object),
                   bool (*equal)(Thread*, object, object))
{
  object n = hashMapFindNode(t, map, key, hash, equal);
  if (n == 0) {
    hashMapInsert(t, map, key, value, hash);
    return true;
  } else {
    return false;
  }
}

object
hashMapRemove(Thread* t, object map, object key,
              uint32_t (*hash)(Thread*, object),
              bool (*equal)(Thread*, object, object));

object
hashMapIterator(Thread* t, object map);

object
hashMapIteratorNext(Thread* t, object it);

void
listAppend(Thread* t, object list, object value);

object
vectorAppend(Thread* t, object vector, object value);

} // vm

#endif//UTIL_H
