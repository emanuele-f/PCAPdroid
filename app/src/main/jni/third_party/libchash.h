/* Copyright (c) 1998 - 2005, Google Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * ---
 * Author: Craig Silverstein
 *
 *  This library is intended to be used for in-memory hash tables,
 *  though it provides rudimentary permanent-storage capabilities.
 *  It attempts to be fast, portable, and small.  The best algorithm
 *  to fulfill these goals is an internal probing hashing algorithm,
 *  as in Knuth, _Art of Computer Programming_, vol III.  Unlike
 *  chained (open) hashing, it doesn't require a pointer for every
 *  item, yet it is still constant time lookup in practice.
 *
 *  Also to save space, we let the contents (both data and key) that
 *  you insert be a union: if the key/data is small, we store it
 *  directly in the hashtable, otherwise we store a pointer to it.
 *  To keep you from having to figure out which, use KEY_PTR and
 *  PTR_KEY to convert between the arguments to these functions and
 *  a pointer to the real data.  For instance:
 *     char key[] = "ab", *key2;
 *     HTItem *bck; HashTable *ht;
 *     HashInsert(ht, PTR_KEY(ht, key), 0);
 *     bck = HashFind(ht, PTR_KEY(ht, "ab"));
 *     key2 = KEY_PTR(ht, bck->key);
 *
 *  There are a rich set of operations supported:
 *     AllocateHashTable() -- Allocates a hashtable structure and
 *                            returns it.
 *        cchKey: if it's a positive number, then each key is a
 *                fixed-length record of that length.  If it's 0,
 *                the key is assumed to be a \0-terminated string.
 *        fSaveKey: normally, you are responsible for allocating
 *                  space for the key.  If this is 1, we make a
 *                  copy of the key for you.
 *     ClearHashTable() -- Removes everything from a hashtable
 *     FreeHashTable() -- Frees memory used by a hashtable
 *
 *     HashFind() -- takes a key (use PTR_KEY) and returns the
 *                   HTItem containing that key, or NULL if the
 *                   key is not in the hashtable.
 *     HashFindLast() -- returns the item found by last HashFind()
 *     HashFindOrInsert() -- inserts the key/data pair if the key
 *                           is not already in the hashtable, or
 *                           returns the appropraite HTItem if it is.
 *     HashFindOrInsertItem() -- takes key/data as an HTItem.
 *     HashInsert() -- adds a key/data pair to the hashtable.  What
 *                     it does if the key is already in the table
 *                     depends on the value of SAMEKEY_OVERWRITE.
 *     HashInsertItem() -- takes key/data as an HTItem.
 *     HashDelete() -- removes a key/data pair from the hashtable,
 *                     if it's there.  RETURNS 1 if it was there,
 *                     0 else.
 *        If you use sparse tables and never delete, the full data
 *        space is available.  Otherwise we steal -2 (maybe -3),
 *        so you can't have data fields with those values.
 *     HashDeleteLast() -- deletes the item returned by the last Find().
 *
 *     HashFirstBucket() -- used to iterate over the buckets in a 
 *                          hashtable.  DON'T INSERT OR DELETE WHILE
 *                          ITERATING!  You can't nest iterations.
 *     HashNextBucket() -- RETURNS NULL at the end of iterating.
 *
 *     HashSetDeltaGoalSize() -- if you're going to insert 1000 items
 *                               at once, call this fn with arg 1000.
 *                               It grows the table more intelligently.
 *
 *     HashSave() -- saves the hashtable to a file.  It saves keys ok,
 *                   but it doesn't know how to interpret the data field,
 *                   so if the data field is a pointer to some complex
 *                   structure, you must send a function that takes a
 *                   file pointer and a pointer to the structure, and
 *                   write whatever you want to write.  It should return
 *                   the number of bytes written.  If the file is NULL,
 *                   it should just return the number of bytes it would
 *                   write, without writing anything.
 *                      If your data field is just an integer, not a
 *                   pointer, just send NULL for the function.
 *     HashLoad() -- loads a hashtable.  It needs a function that takes
 *                   a file and the size of the structure, and expects
 *                   you to read in the structure and return a pointer
 *                   to it.  You must do memory allocation, etc.  If
 *                   the data is just a number, send NULL.
 *     HashLoadKeys() -- unlike HashLoad(), doesn't load the data off disk
 *                       until needed.  This saves memory, but if you look
 *                       up the same key a lot, it does a disk access each
 *                       time.
 *        You can't do Insert() or Delete() on hashtables that were loaded
 *        from disk.
 */

#include <sys/types.h>         /* includes definition of "ulong", we hope */
#define ulong u_long

#define MAGIC_KEY             "CHsh"   /* when we save the file */

#ifndef LOG_WORD_SIZE                  /* 5 for 32 bit words, 6 for 64 */
#if defined (__LP64__) || defined (_LP64)
#define LOG_WORD_SIZE          6       /* log_2(sizeof(ulong)) [in bits] */
#else
#define LOG_WORD_SIZE          5       /* log_2(sizeof(ulong)) [in bits] */
#endif
#endif

   /* The following gives a speed/time tradeoff: how many buckets are  *
    * in each bin.  0 gives 32 buckets/bin, which is a good number.    */
#ifndef LOG_BM_WORDS
#define LOG_BM_WORDS        0      /* each group has 2^L_B_W * 32 buckets */
#endif

   /* The following are all parameters that affect performance. */
#ifndef JUMP
#define JUMP(key, offset)   ( ++(offset) )  /* ( 1 ) for linear hashing */
#endif
#ifndef Table
#define Table(x)            Sparse##x       /* Dense##x for dense tables */
#endif
#ifndef FAST_DELETE
#define FAST_DELETE         0      /* if it's 1, we never shrink the ht */
#endif
#ifndef SAMEKEY_OVERWRITE
#define SAMEKEY_OVERWRITE   1      /* overwrite item with our key on insert? */
#endif
#ifndef OCCUPANCY_PCT
#define OCCUPANCY_PCT       0.5    /* large PCT means smaller and slower */
#endif
#ifndef MIN_HASH_SIZE
#define MIN_HASH_SIZE       512    /* ht size when first created */
#endif
   /* When deleting a bucket, we can't just empty it (future hashes  *
    * may fail); instead we set the data field to DELETED.  Thus you *
    * should set DELETED to a data value you never use.  Better yet, *
    * if you don't need to delete, define INSERT_ONLY.               */
#ifndef INSERT_ONLY
#define DELETED                   -2UL
#define IS_BCK_DELETED(bck)       ( (bck) && (bck)->data == DELETED )
#define SET_BCK_DELETED(ht, bck)  do { (bck)->data = DELETED;                \
                                       FREE_KEY(ht, (bck)->key); } while ( 0 )
#else
#define IS_BCK_DELETED(bck)       0
#define SET_BCK_DELETED(ht, bck)  \
   do { fprintf(stderr, "Deletion not supported for insert-only hashtable\n");\
        exit(2); } while ( 0 )
#endif

   /* We need the following only for dense buckets (Dense##x above).  *
    * If you need to, set this to a value you'll never use for data.  */
#define EMPTY -3UL                /* steal more of the bck->data space */


   /* This is what an item is.  Either can be cast to a pointer. */
typedef struct {
   ulong data;        /* 4 bytes for data: either a pointer or an integer */
   ulong key;         /* 4 bytes for the key: either a pointer or an int */
} HTItem;

struct Table(Bin);                            /* defined in chash.c, I hope */
struct Table(Iterator);
typedef struct Table(Bin)       Table;        /* Expands to SparseBin, etc */
typedef struct Table(Iterator)  TableIterator;

   /* for STORES_PTR to work ok, cchKey MUST BE DEFINED 1st, cItems 2nd! */
typedef struct HashTable {
   ulong cchKey;        /* the length of the key, or if it's \0 terminated */
   ulong cItems;        /* number of items currently in the hashtable */
   ulong cDeletedItems; /* # of buckets holding DELETE in the hashtable */
   ulong cBuckets;      /* size of the table */
   Table *table;        /* The actual contents of the hashtable */
   int fSaveKeys;       /* 1 if we copy keys locally; 2 if keys in one block */
   int cDeltaGoalSize;  /* # of coming inserts (or deletes, if <0) we expect */
   HTItem *posLastFind; /* position of last Find() command */
   TableIterator *iter; /* used in First/NextBucket */

   FILE *fpData;        /* if non-NULL, what item->data points into */
   char * (*dataRead)(FILE *, int);   /* how to load data from disk */
   HTItem bckData;      /* holds data after being loaded from disk */
} HashTable;

   /* Small keys are stored and passed directly, but large keys are
    * stored and passed as pointers.  To make it easier to remember
    * what to pass, we provide two functions:
    *   PTR_KEY: give it a pointer to your data, and it returns
    *            something appropriate to send to Hash() functions or
    *            be stored in a data field.
    *   KEY_PTR: give it something returned by a Hash() routine, and
    *            it returns a (char *) pointer to the actual data.
    */
#define HashKeySize(ht)   ( ((ulong *)(ht))[0] )  /* this is how we inline */
#define HashSize(ht)      ( ((ulong *)(ht))[1] )  /* ...a la C++ :-) */

#define STORES_PTR(ht)    ( HashKeySize(ht) == 0 || \
			    HashKeySize(ht) > sizeof(ulong) )
#define KEY_PTR(ht, key)  ( STORES_PTR(ht) ? (char *)(key) : (char *)&(key) )
#ifdef DONT_HAVE_TO_WORRY_ABOUT_BUS_ERRORS
#define PTR_KEY(ht, ptr)  ( STORES_PTR(ht) ? (ulong)(ptr) : *(ulong *)(ptr) )
#else
#define PTR_KEY(ht, ptr)  ( STORES_PTR(ht) ? (ulong)(ptr) : HTcopy((char *)ptr))
#endif


   /* Function prototypes */
unsigned long HTcopy(char *pul);         /* for PTR_KEY, not for users */

struct HashTable *AllocateHashTable(int cchKey, int fSaveKeys);
void ClearHashTable(struct HashTable *ht);
void FreeHashTable(struct HashTable *ht);

HTItem *HashFind(struct HashTable *ht, ulong key);
HTItem *HashFindLast(struct HashTable *ht);
HTItem *HashFindOrInsert(struct HashTable *ht, ulong key, ulong dataInsert);
HTItem *HashFindOrInsertItem(struct HashTable *ht, HTItem *pItem);

HTItem *HashInsert(struct HashTable *ht, ulong key, ulong data);
HTItem *HashInsertItem(struct HashTable *ht, HTItem *pItem);

int HashDelete(struct HashTable *ht, ulong key);
int HashDeleteLast(struct HashTable *ht);

HTItem *HashFirstBucket(struct HashTable *ht);
HTItem *HashNextBucket(struct HashTable *ht);

int HashSetDeltaGoalSize(struct HashTable *ht, int delta);

void HashSave(FILE *fp, struct HashTable *ht, int (*write)(FILE *, char *));
struct HashTable *HashLoad(FILE *fp, char * (*read)(FILE *, int));
struct HashTable *HashLoadKeys(FILE *fp, char * (*read)(FILE *, int));
