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
 *
 *  See libchash.h for parameters you can modify.  Make sure LOG_WORD_SIZE
 *  is defined correctly for your machine!  (5 for 32 bit words, 6 for 64).
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>       /* for strcmp, memcmp, etc */
#include <sys/types.h>    /* ULTRIX needs this for in.h */
#include <netinet/in.h>   /* for reading/writing hashtables */
#include <assert.h>
#include "libchash.h"     /* all the types */

   /* if keys are stored directly but cchKey is less than sizeof(ulong), */
   /* this cuts off the bits at the end */
char grgKeyTruncMask[sizeof(ulong)][sizeof(ulong)];
#define KEY_TRUNC(ht, key)                                                    \
   ( STORES_PTR(ht) || (ht)->cchKey == sizeof(ulong)                          \
       ? (key) : ((key) & *(ulong *)&(grgKeyTruncMask[(ht)->cchKey][0])) )

   /* round num up to a multiple of wordsize.  (LOG_WORD_SIZE-3 is in bytes) */
#define WORD_ROUND(num)         ( ((num-1) | ((1<<(LOG_WORD_SIZE-3))-1)) + 1 )
#define NULL_TERMINATED  0    /* val of cchKey if keys are null-term strings */

   /* Useful operations we do to keys: compare them, copy them, free them */

#define KEY_CMP(ht, key1, key2)      ( !STORES_PTR(ht)  ? (key1) - (key2) :   \
                                       (key1) == (key2) ? 0 :                 \
                                       HashKeySize(ht) == NULL_TERMINATED ?   \
                                          strcmp((char *)key1, (char *)key2) :\
                                          memcmp((void *)key1, (void *)key2,  \
						 HashKeySize(ht)) )

#define COPY_KEY(ht, keyTo, keyFrom) do                                       \
   if ( !STORES_PTR(ht) || !(ht)->fSaveKeys )                                 \
      (keyTo) = (keyFrom);                     /* just copy pointer or info */\
   else if ( (ht)->cchKey == NULL_TERMINATED )        /* copy 0-term.ed str */\
   {                                                                          \
      (keyTo) = (ulong)HTsmalloc( WORD_ROUND(strlen((char *)(keyFrom))+1) );  \
      strcpy((char *)(keyTo), (char *)(keyFrom));                             \
   }                                                                          \
   else                                                                       \
   {                                                                          \
      (keyTo) = (ulong) HTsmalloc( WORD_ROUND((ht)->cchKey) );                \
      memcpy( (char *)(keyTo), (char *)(keyFrom), (ht)->cchKey);              \
   }                                                                          \
   while ( 0 )

#define FREE_KEY(ht, key) do                                                  \
   if ( STORES_PTR(ht) && (ht)->fSaveKeys )                                   \
     if ( (ht)->cchKey == NULL_TERMINATED )                                   \
        HTfree((char *)(key), WORD_ROUND(strlen((char *)(key))+1));           \
     else                                                                     \
        HTfree((char *)(key), WORD_ROUND((ht)->cchKey));                      \
   while ( 0 )

   /* the following are useful for bitmaps */
   /* Format is like this (if 1 word = 4 bits):  3210 7654 ba98 fedc ... */
typedef ulong          HTBitmapPart;  /* this has to be unsigned, for >> */
typedef HTBitmapPart   HTBitmap[1<<LOG_BM_WORDS];
typedef ulong          HTOffset; /* something big enough to hold offsets */

#define BM_BYTES(cBuckets)   /* we must ensure it's a multiple of word size */\
   ( (((cBuckets) + 8*sizeof(ulong)-1) >> LOG_WORD_SIZE) << (LOG_WORD_SIZE-3) )
#define MOD2(i, logmod)      ( (i) & ((1<<(logmod))-1) )
#define DIV_NUM_ENTRIES(i)   ( (i) >> LOG_WORD_SIZE )
#define MOD_NUM_ENTRIES(i)   ( MOD2(i, LOG_WORD_SIZE) )
#define MODBIT(i)            ( ((ulong)1) << MOD_NUM_ENTRIES(i) )

#define TEST_BITMAP(bm, i)   ( (bm)[DIV_NUM_ENTRIES(i)] & MODBIT(i) ? 1 : 0 )
#define SET_BITMAP(bm, i)    (bm)[DIV_NUM_ENTRIES(i)] |= MODBIT(i)
#define CLEAR_BITMAP(bm, i)  (bm)[DIV_NUM_ENTRIES(i)] &= ~MODBIT(i)

   /* the following are useful for reading and writing hashtables */
#define READ_UL(fp, data)                  \
   do {                                    \
      long _ul;                            \
      fread(&_ul, sizeof(_ul), 1, (fp));   \
      data = ntohl(_ul);                   \
   } while (0)

#define WRITE_UL(fp, data)                 \
   do {                                    \
      long _ul = htonl((long)(data));      \
      fwrite(&_ul, sizeof(_ul), 1, (fp));  \
   } while (0)

   /* Moves data from disk to memory if necessary.  Note dataRead cannot be  *
    * NULL, because then we might as well (and do) load the data into memory */
#define LOAD_AND_RETURN(ht, loadCommand)     /* lC returns an HTItem * */     \
   if ( !(ht)->fpData )          /* data is stored in memory */               \
      return (loadCommand);                                                   \
   else                          /* must read data off of disk */             \
   {                                                                          \
      int cchData;                                                            \
      HTItem *bck;                                                            \
      if ( (ht)->bckData.data )  free((char *)(ht)->bckData.data);            \
      ht->bckData.data = (ulong)NULL;   /* needed if loadCommand fails */     \
      bck = (loadCommand);                                                    \
      if ( bck == NULL )          /* loadCommand failed: key not found */     \
         return NULL;                                                         \
      else                                                                    \
         (ht)->bckData = *bck;                                                \
      fseek(ht->fpData, (ht)->bckData.data, SEEK_SET);                        \
      READ_UL((ht)->fpData, cchData);                                         \
      (ht)->bckData.data = (ulong)(ht)->dataRead((ht)->fpData, cchData);      \
      return &((ht)->bckData);                                                \
   }


/* ======================================================================== */
/*                          UTILITY ROUTINES                                */
/*                       ----------------------                             */

/* HTsmalloc() -- safe malloc
 *    allocates memory, or crashes if the allocation fails.
 */
static void *HTsmalloc(unsigned long size)
{
   void *retval;

   if ( size == 0 )
      return NULL;
   retval = (void *)malloc(size);
   if ( !retval )
   {
      fprintf(stderr, "HTsmalloc: Unable to allocate %lu bytes of memory\n",
	      size);
      exit(1);
   }
   return retval;
}

/* HTscalloc() -- safe calloc
 *    allocates memory and initializes it to 0, or crashes if
 *    the allocation fails.
 */
static void *HTscalloc(unsigned long size)
{
   void *retval;

   retval = (void *)calloc(size, 1);
   if ( !retval && size > 0 )
   {
      fprintf(stderr, "HTscalloc: Unable to allocate %lu bytes of memory\n",
	      size);
      exit(1);
   }
   return retval;
}

/* HTsrealloc() -- safe calloc
 *    grows the amount of memory from a source, or crashes if
 *    the allocation fails.
 */
static void *HTsrealloc(void *ptr, unsigned long new_size, long delta)
{
   if ( ptr == NULL )
      return HTsmalloc(new_size);
   ptr = realloc(ptr, new_size);
   if ( !ptr && new_size > 0 )
   {
      fprintf(stderr, "HTsrealloc: Unable to reallocate %lu bytes of memory\n",
	      new_size);
      exit(1);
   }
   return ptr;
}

/* HTfree() -- keep track of memory use
 *    frees memory using free, but updates count of how much memory
 *    is being used.
 */
static void HTfree(void *ptr, unsigned long size)
{
   if ( size > 0 )         /* some systems seem to not like freeing NULL */
      free(ptr);
}

/*************************************************************************\
| HTcopy()                                                                |
|     Sometimes we interpret data as a ulong.  But ulongs must be         |
|     aligned on some machines, so instead of casting we copy.            |
\*************************************************************************/

unsigned long HTcopy(char *ul)
{
   unsigned long retval;

   memcpy(&retval, ul, sizeof(retval));
   return retval;
}

/*************************************************************************\
| HTSetupKeyTrunc()                                                       |
|     If keys are stored directly but cchKey is less than                 |
|     sizeof(ulong), this cuts off the bits at the end.                   |
\*************************************************************************/
   
static void HTSetupKeyTrunc(void)
{
   int i, j;

   for ( i = 0; i < sizeof(unsigned long); i++ )
      for ( j = 0; j < sizeof(unsigned long); j++ )
	 grgKeyTruncMask[i][j] = j < i ? 255 : 0;   /* chars have 8 bits */
}


/* ======================================================================== */
/*                            TABLE ROUTINES                                */
/*                         --------------------                             */

/*  The idea is that a hashtable with (logically) t buckets is divided
 *  into t/M groups of M buckets each.  (M is a constant set in
 *  LOG_BM_WORDS for efficiency.)  Each group is stored sparsely.
 *  Thus, inserting into the table causes some array to grow, which is
 *  slow but still constant time.  Lookup involves doing a
 *  logical-position-to-sparse-position lookup, which is also slow but
 *  constant time.  The larger M is, the slower these operations are
 *  but the less overhead (slightly).
 *
 *  To store the sparse array, we store a bitmap B, where B[i] = 1 iff
 *  bucket i is non-empty.  Then to look up bucket i we really look up
 *  array[# of 1s before i in B].  This is constant time for fixed M.
 *
 *  Terminology: the position of an item in the overall table (from
 *  1 .. t) is called its "location."  The logical position in a group
 *  (from 1 .. M ) is called its "position."  The actual location in
 *  the array (from 1 .. # of non-empty buckets in the group) is
 *  called its "offset."
 *
 *  The following operations are supported:
 *     o Allocate an array with t buckets, all empty
 *     o Free a array (but not whatever was stored in the buckets)
 *     o Tell whether or not a bucket is empty
 *     o Return a bucket with a given location
 *     o Set the value of a bucket at a given location
 *     o Iterate through all the buckets in the array
 *     o Read and write an occupancy bitmap to disk
 *     o Return how much memory is being allocated by the array structure
 */

#ifndef SparseBucket            /* by default, each bucket holds an HTItem */
#define SparseBucket            HTItem
#endif

typedef struct SparseBin {
   SparseBucket *binSparse;
   HTBitmap bmOccupied;      /* bmOccupied[i] is 1 if bucket i has an item */
   short cOccupied;          /* size of binSparse; useful for iterators, eg */
} SparseBin;

typedef struct SparseIterator {
   long posGroup;
   long posOffset;
   SparseBin *binSparse;     /* state info, to avoid args for NextBucket() */
   ulong cBuckets;
} SparseIterator;

#define LOG_LOW_BIN_SIZE        ( LOG_BM_WORDS+LOG_WORD_SIZE )
#define SPARSE_GROUPS(cBuckets) ( (((cBuckets)-1) >> LOG_LOW_BIN_SIZE) + 1 )

   /* we need a small function to figure out # of items set in the bm */
static HTOffset EntriesUpto(HTBitmapPart *bm, int i)
{                                       /* returns # of set bits in 0..i-1 */
   HTOffset retval = 0; 
   static HTOffset rgcBits[256] =             /* # of bits set in one char */
      {0, 1, 1, 2, 1, 2, 2, 3, 1, 2, 2, 3, 2, 3, 3, 4,
       1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5,
       1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5,
       2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
       1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5,
       2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
       2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
       3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
       1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5,
       2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
       2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
       3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
       2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
       3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
       3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
       4, 5, 5, 6, 5, 6, 6, 7, 5, 6, 6, 7, 6, 7, 7, 8};

   if ( i == 0 ) return 0;
   for ( ; i > sizeof(*bm)*8; i -= sizeof(*bm)*8, bm++ )
   {                                       /* think of it as loop unrolling */
#if LOG_WORD_SIZE >= 3                     /* 1 byte per word, or more */
      retval += rgcBits[*bm & 255];        /* get the low byte */
#if LOG_WORD_SIZE >= 4                     /* at least 2 bytes */
      retval += rgcBits[(*bm >> 8) & 255];
#if LOG_WORD_SIZE >= 5                     /* at least 4 bytes */
      retval += rgcBits[(*bm >> 16) & 255];
      retval += rgcBits[(*bm >> 24) & 255];
#if LOG_WORD_SIZE >= 6                     /* 8 bytes! */
      retval += rgcBits[(*bm >> 32) & 255];
      retval += rgcBits[(*bm >> 40) & 255];
      retval += rgcBits[(*bm >> 48) & 255];
      retval += rgcBits[(*bm >> 56) & 255];
#if LOG_WORD_SIZE >= 7                     /* not a concern for a while... */
#error Need to rewrite EntriesUpto to support such big words
#endif   /* >8 bytes */
#endif   /* 8 bytes */
#endif   /* 4 bytes */
#endif   /* 2 bytes */
#endif   /* 1 byte */
   }
   switch ( i ) {                         /* from 0 to 63 */
      case 0:
	 return retval;
#if LOG_WORD_SIZE >= 3                     /* 1 byte per word, or more */
      case 1: case 2: case 3: case 4: case 5: case 6: case 7: case 8:
	 return (retval + rgcBits[*bm & ((1 << i)-1)]);
#if LOG_WORD_SIZE >= 4                     /* at least 2 bytes */
      case 9: case 10: case 11: case 12: case 13: case 14: case 15: case 16:
	 return (retval + rgcBits[*bm & 255] + 
		 rgcBits[(*bm >> 8) & ((1 << (i-8))-1)]);
#if LOG_WORD_SIZE >= 5                     /* at least 4 bytes */
      case 17: case 18: case 19: case 20: case 21: case 22: case 23: case 24:
	 return (retval + rgcBits[*bm & 255] + rgcBits[(*bm >> 8) & 255] +
		 rgcBits[(*bm >> 16) & ((1 << (i-16))-1)]);
      case 25: case 26: case 27: case 28: case 29: case 30: case 31: case 32:
	 return (retval + rgcBits[*bm & 255] + rgcBits[(*bm >> 8) & 255] +
		 rgcBits[(*bm >> 16) & 255] + 
		 rgcBits[(*bm >> 24) & ((1 << (i-24))-1)]);
#if LOG_WORD_SIZE >= 6                     /* 8 bytes! */
      case 33: case 34: case 35: case 36: case 37: case 38: case 39: case 40:
	 return (retval + rgcBits[*bm & 255] + rgcBits[(*bm >> 8) & 255] +
		 rgcBits[(*bm >> 16) & 255] + rgcBits[(*bm >> 24) & 255] + 
		 rgcBits[(*bm >> 32) & ((1 << (i-32))-1)]);
      case 41: case 42: case 43: case 44: case 45: case 46: case 47: case 48:
	 return (retval + rgcBits[*bm & 255] + rgcBits[(*bm >> 8) & 255] +
		 rgcBits[(*bm >> 16) & 255] + rgcBits[(*bm >> 24) & 255] + 
		 rgcBits[(*bm >> 32) & 255] +
		 rgcBits[(*bm >> 40) & ((1 << (i-40))-1)]);
      case 49: case 50: case 51: case 52: case 53: case 54: case 55: case 56:
	 return (retval + rgcBits[*bm & 255] + rgcBits[(*bm >> 8) & 255] +
		 rgcBits[(*bm >> 16) & 255] + rgcBits[(*bm >> 24) & 255] + 
		 rgcBits[(*bm >> 32) & 255] + rgcBits[(*bm >> 40) & 255] +
		 rgcBits[(*bm >> 48) & ((1 << (i-48))-1)]);
      case 57: case 58: case 59: case 60: case 61: case 62: case 63: case 64:
	 return (retval + rgcBits[*bm & 255] + rgcBits[(*bm >> 8) & 255] +
		 rgcBits[(*bm >> 16) & 255] + rgcBits[(*bm >> 24) & 255] + 
		 rgcBits[(*bm >> 32) & 255] + rgcBits[(*bm >> 40) & 255] +
		 rgcBits[(*bm >> 48) & 255] + 
		 rgcBits[(*bm >> 56) & ((1 << (i-56))-1)]);
#endif   /* 8 bytes */
#endif   /* 4 bytes */
#endif   /* 2 bytes */
#endif   /* 1 byte */
   }
   assert("" == "word size is too big in EntriesUpto()");
   return -1;
}
#define SPARSE_POS_TO_OFFSET(bm, i)   ( EntriesUpto(&((bm)[0]), i) )
#define SPARSE_BUCKET(bin, location)  \
   ( (bin)[(location) >> LOG_LOW_BIN_SIZE].binSparse +                     \
      SPARSE_POS_TO_OFFSET((bin)[(location)>>LOG_LOW_BIN_SIZE].bmOccupied, \
		           MOD2(location, LOG_LOW_BIN_SIZE)) )


/*************************************************************************\
| SparseAllocate()                                                        |
| SparseFree()                                                            |
|     Allocates, sets-to-empty, and frees a sparse array.  All you need   |
|     to tell me is how many buckets you want.  I return the number of    |
|     buckets I actually allocated, setting the array as a parameter.     |
|     Note that you have to set auxilliary parameters, like cOccupied.    |
\*************************************************************************/

static ulong SparseAllocate(SparseBin **pbinSparse, ulong cBuckets)
{
   int cGroups = SPARSE_GROUPS(cBuckets);

   *pbinSparse = (SparseBin *) HTscalloc(sizeof(**pbinSparse) * cGroups);
   return cGroups << LOG_LOW_BIN_SIZE;
}

static SparseBin *SparseFree(SparseBin *binSparse, ulong cBuckets)
{
   ulong iGroup, cGroups = SPARSE_GROUPS(cBuckets);

   for ( iGroup = 0; iGroup < cGroups; iGroup++ )
      HTfree(binSparse[iGroup].binSparse, (sizeof(*binSparse[iGroup].binSparse)
					   * binSparse[iGroup].cOccupied));
   HTfree(binSparse, sizeof(*binSparse) * cGroups);
   return NULL;
}

/*************************************************************************\
| SparseIsEmpty()                                                         |
| SparseFind()                                                            |
|     You give me a location (ie a number between 1 and t), and I         |
|     return the bucket at that location, or NULL if the bucket is        |
|     empty.  It's OK to call Find() on an empty table.                   |
\*************************************************************************/

static int SparseIsEmpty(SparseBin *binSparse, ulong location)
{
   return !TEST_BITMAP(binSparse[location>>LOG_LOW_BIN_SIZE].bmOccupied,
		       MOD2(location, LOG_LOW_BIN_SIZE));
}

static SparseBucket *SparseFind(SparseBin *binSparse, ulong location)
{
   if ( SparseIsEmpty(binSparse, location) )
      return NULL;
   return SPARSE_BUCKET(binSparse, location);
}

/*************************************************************************\
| SparseInsert()                                                          |
|     You give me a location, and contents to put there, and I insert     |
|     into that location and RETURN a pointer to the location.  If        |
|     bucket was already occupied, I write over the contents only if      |
|     *pfOverwrite is 1.  We set *pfOverwrite to 1 if there was someone   |
|     there (whether or not we overwrote) and 0 else.                     |
\*************************************************************************/

static SparseBucket *SparseInsert(SparseBin *binSparse, SparseBucket *bckInsert,
				  ulong location, int *pfOverwrite)
{
   SparseBucket *bckPlace;
   HTOffset offset;

   bckPlace = SparseFind(binSparse, location);
   if ( bckPlace )                /* means we replace old contents */
   {
      if ( *pfOverwrite )
	 *bckPlace = *bckInsert;
      *pfOverwrite = 1;
      return bckPlace;
   }

   binSparse += (location >> LOG_LOW_BIN_SIZE);
   offset = SPARSE_POS_TO_OFFSET(binSparse->bmOccupied,
				 MOD2(location, LOG_LOW_BIN_SIZE));
   binSparse->binSparse = (SparseBucket *) 
      HTsrealloc(binSparse->binSparse,
		 sizeof(*binSparse->binSparse) * ++binSparse->cOccupied,
		 sizeof(*binSparse->binSparse));
   memmove(binSparse->binSparse + offset+1,
	   binSparse->binSparse + offset,
	   (binSparse->cOccupied-1 - offset) * sizeof(*binSparse->binSparse));
   binSparse->binSparse[offset] = *bckInsert;
   SET_BITMAP(binSparse->bmOccupied, MOD2(location, LOG_LOW_BIN_SIZE));
   *pfOverwrite = 0;
   return binSparse->binSparse + offset;
}

/*************************************************************************\
| SparseFirstBucket()                                                     |
| SparseNextBucket()                                                      |
| SparseCurrentBit()                                                      |
|     Iterate through the occupied buckets of a dense hashtable.  You     |
|     must, of course, have allocated space yourself for the iterator.    |
\*************************************************************************/

static SparseBucket *SparseNextBucket(SparseIterator *iter)
{
   if ( iter->posOffset != -1 &&      /* not called from FirstBucket()? */
        (++iter->posOffset < iter->binSparse[iter->posGroup].cOccupied) )
      return iter->binSparse[iter->posGroup].binSparse + iter->posOffset;

   iter->posOffset = 0;                         /* start the next group */
   for ( iter->posGroup++;  iter->posGroup < SPARSE_GROUPS(iter->cBuckets);
	 iter->posGroup++ )
      if ( iter->binSparse[iter->posGroup].cOccupied > 0 )
	 return iter->binSparse[iter->posGroup].binSparse; /* + 0 */
   return NULL;                      /* all remaining groups were empty */
}

static SparseBucket *SparseFirstBucket(SparseIterator *iter,
				       SparseBin *binSparse, ulong cBuckets)
{
   iter->binSparse = binSparse;        /* set it up for NextBucket() */
   iter->cBuckets = cBuckets;
   iter->posOffset = -1;               /* when we advance, we're at 0 */
   iter->posGroup = -1;
   return SparseNextBucket(iter);
}

/*************************************************************************\
| SparseWrite()                                                           |
| SparseRead()                                                            |
|     These are routines for storing a sparse hashtable onto disk.  We    |
|     store the number of buckets and a bitmap indicating which buckets   |
|     are allocated (occupied).  The actual contents of the buckets       |
|     must be stored separately.                                          |
\*************************************************************************/

static void SparseWrite(FILE *fp, SparseBin *binSparse, ulong cBuckets)
{
   ulong i, j;

   WRITE_UL(fp, cBuckets);
   for ( i = 0; i < SPARSE_GROUPS(cBuckets); i++ )
      for ( j = 0; j < (1<<LOG_BM_WORDS); j++ )
	 WRITE_UL(fp, binSparse[i].bmOccupied[j]);
}

static ulong SparseRead(FILE *fp, SparseBin **pbinSparse)
{
   ulong i, j, cBuckets;

   READ_UL(fp, cBuckets);                /* actually, cBuckets is stored */
   cBuckets = SparseAllocate(pbinSparse, cBuckets);
   for ( i = 0; i < SPARSE_GROUPS(cBuckets); i++ )
   {
      for ( j = 0; j < (1<<LOG_BM_WORDS); j++ )
	 READ_UL(fp, (*pbinSparse)[i].bmOccupied[j]);
      (*pbinSparse)[i].cOccupied =
	 SPARSE_POS_TO_OFFSET((*pbinSparse)[i].bmOccupied,1<<LOG_LOW_BIN_SIZE);
      (*pbinSparse)[i].binSparse =
	 (SparseBucket *) HTsmalloc(sizeof(*((*pbinSparse)[i].binSparse)) *
				    (*pbinSparse)[i].cOccupied);
   }
   return cBuckets;
}

/*************************************************************************\
| SparseMemory()                                                          |
|     SparseMemory() tells us how much memory is being allocated for      |
|     the dense table.  You need to tell me not only how many buckets     |
|     there are, but how many are occupied.                               |
\*************************************************************************/

static ulong SparseMemory(ulong cBuckets, ulong cOccupied)
{
   return ( cOccupied * sizeof(SparseBucket) +
	    SPARSE_GROUPS(cBuckets) * sizeof(SparseBin) );
}


/*  Just for fun, I also provide support for dense tables.  These are
 *  just regulr arrays.  Access is fast, but they can get big.
 *  Use Table(x) at the top of chash.h to decide which you want.
 *  A disadvantage is we need to steal more of the data space for
 *  indicating empty buckets.  We choose -3.
 */

#ifndef DenseBucket             /* by default, each bucket holds an HTItem */
#define DenseBucket             HTItem
#endif

typedef struct DenseBin {       /* needs to be a struct for C typing reasons */
   DenseBucket *rgBuckets;      /* A bin is an array of buckets */
} DenseBin;

typedef struct DenseIterator {
   long pos;               /* the actual iterator */
   DenseBin *bin;          /* state info, to avoid args for NextBucket() */
   ulong cBuckets;
} DenseIterator;

#define DENSE_IS_EMPTY(bin, i)     ( (bin)[i].data == EMPTY )
#define DENSE_SET_EMPTY(bin, i)    (bin)[i].data = EMPTY      /* fks-hash.h */
#define DENSE_SET_OCCUPIED(bin, i) (bin)[i].data = 1          /* not EMPTY */

static void DenseClear(DenseBin *bin, ulong cBuckets)
{
   while ( cBuckets-- )
      DENSE_SET_EMPTY(bin->rgBuckets, cBuckets);
}

static ulong DenseAllocate(DenseBin **pbin, ulong cBuckets)
{
   *pbin = (DenseBin *) HTsmalloc(sizeof(*pbin));
   (*pbin)->rgBuckets = (DenseBucket *) HTsmalloc(sizeof(*(*pbin)->rgBuckets)
						  * cBuckets);
   DenseClear(*pbin, cBuckets);
   return cBuckets;
}

static DenseBin *DenseFree(DenseBin *bin, ulong cBuckets)
{
   HTfree(bin->rgBuckets, sizeof(*bin->rgBuckets) * cBuckets);
   HTfree(bin, sizeof(*bin));
   return NULL;
}

static int DenseIsEmpty(DenseBin *bin, ulong location)
{
   return DENSE_IS_EMPTY(bin->rgBuckets, location);
}

static DenseBucket *DenseFind(DenseBin *bin, ulong location)
{
   if ( DenseIsEmpty(bin, location) )
      return NULL;
   return bin->rgBuckets + location;
}

static DenseBucket *DenseInsert(DenseBin *bin, DenseBucket *bckInsert,
				ulong location, int *pfOverwrite)
{
   DenseBucket *bckPlace;

   bckPlace = DenseFind(bin, location);
   if ( bckPlace )                /* means something is already there */
   {
      if ( *pfOverwrite )
	 *bckPlace = *bckInsert;
      *pfOverwrite = 1;           /* set to 1 to indicate someone was there */
      return bckPlace;
   }
   else
   {
      bin->rgBuckets[location] = *bckInsert;
      *pfOverwrite = 0;
      return bin->rgBuckets + location;
   }
}

static DenseBucket *DenseNextBucket(DenseIterator *iter)
{
   for ( iter->pos++; iter->pos < iter->cBuckets; iter->pos++ )
      if ( !DenseIsEmpty(iter->bin, iter->pos) )
	 return iter->bin->rgBuckets + iter->pos;
   return NULL;                        /* all remaining groups were empty */
}

static DenseBucket *DenseFirstBucket(DenseIterator *iter,
				     DenseBin *bin, ulong cBuckets)
{
   iter->bin = bin;                    /* set it up for NextBucket() */
   iter->cBuckets = cBuckets;
   iter->pos = -1;                     /* thus the next bucket will be 0 */
   return DenseNextBucket(iter);
}

static void DenseWrite(FILE *fp, DenseBin *bin, ulong cBuckets)
{
   ulong pos = 0, bit, bm;

   WRITE_UL(fp, cBuckets);
   while ( pos < cBuckets )
   {
      bm = 0;
      for ( bit = 0; bit < 8*sizeof(ulong); bit++ )
      {
	 if ( !DenseIsEmpty(bin, pos) )
	    SET_BITMAP(&bm, bit);                /* in fks-hash.h */
	 if ( ++pos == cBuckets )
	    break;
      }
      WRITE_UL(fp, bm);
   }
}

static ulong DenseRead(FILE *fp, DenseBin **pbin)
{
   ulong pos = 0, bit, bm, cBuckets;

   READ_UL(fp, cBuckets);
   cBuckets = DenseAllocate(pbin, cBuckets);
   while ( pos < cBuckets )
   {
      READ_UL(fp, bm);
      for ( bit = 0; bit < 8*sizeof(ulong); bit++ )
      {
	 if ( TEST_BITMAP(&bm, bit) )            /* in fks-hash.h */
	    DENSE_SET_OCCUPIED((*pbin)->rgBuckets, pos);
	 else
	    DENSE_SET_EMPTY((*pbin)->rgBuckets, pos);
	 if ( ++pos == cBuckets )
	    break;
      }
   }
   return cBuckets;
}

static ulong DenseMemory(ulong cBuckets, ulong cOccupied)
{
   return cBuckets * sizeof(DenseBucket);
}


/* ======================================================================== */
/*                          HASHING ROUTINES                                */
/*                       ----------------------                             */

/*  Implements a simple quadratic hashing scheme.  We have a single hash
 *  table of size t and a single hash function h(x).  When inserting an
 *  item, first we try h(x) % t.  If it's occupied, we try h(x) + 
 *  i*(i-1)/2 % t for increasing values of i until we hit a not-occupied
 *  space.  To make this dynamic, we double the size of the hash table as
 *  soon as more than half the cells are occupied.  When deleting, we can
 *  choose to shrink the hashtable when less than a quarter of the
 *  cells are occupied, or we can choose never to shrink the hashtable.
 *  For lookup, we check h(x) + i*(i-1)/2 % t (starting with i=0) until
 *  we get a match or we hit an empty space.  Note that as a result,
 *  we can't make a cell empty on deletion, or lookups may end prematurely.
 *  Instead we mark the cell as "deleted."  We thus steal the value
 *  DELETED as a possible "data" value.  As long as data are pointers,
 *  that's ok.
 *     The hash increment we use, i(i-1)/2, is not the standard quadratic
 *  hash increment, which is i^2.  i(i-1)/2 covers the entire bucket space
 *  when the hashtable size is a power of two, as it is for us.  In fact,
 *  the first n probes cover n distinct buckets; then it repeats.  This
 *  guarantees insertion will always succeed.
 *     If you linear hashing, set JUMP in chash.h.  You can also change
 *  various other parameters there.
 */

/*************************************************************************\
| Hash()                                                                  |
|     The hash function I use is due to Bob Jenkins (see                  |
|     http://burtleburtle.net/bob/hash/evahash.html                       |
|     According to http://burtleburtle.net/bob/c/lookup2.c,               |
|     his implementation is public domain.)                               |
|     It takes 36 instructions, in 18 cycles if you're lucky.             |
|        hashing depends on the fact the hashtable size is always a       |
|     power of 2.  cBuckets is probably ht->cBuckets.                     |
\*************************************************************************/

#if LOG_WORD_SIZE == 5                      /* 32 bit words */

#define mix(a,b,c) \
{ \
  a -= b; a -= c; a ^= (c>>13); \
  b -= c; b -= a; b ^= (a<<8); \
  c -= a; c -= b; c ^= (b>>13); \
  a -= b; a -= c; a ^= (c>>12);  \
  b -= c; b -= a; b ^= (a<<16); \
  c -= a; c -= b; c ^= (b>>5); \
  a -= b; a -= c; a ^= (c>>3);  \
  b -= c; b -= a; b ^= (a<<10); \
  c -= a; c -= b; c ^= (b>>15); \
}
#ifdef WORD_HASH                 /* play with this on little-endian machines */
#define WORD_AT(ptr)    ( *(ulong *)(ptr) )
#else
#define WORD_AT(ptr)    ( (ptr)[0] + ((ulong)(ptr)[1]<<8) + \
			  ((ulong)(ptr)[2]<<16) + ((ulong)(ptr)[3]<<24) )
#endif

#elif LOG_WORD_SIZE == 6        /* 64 bit words */

#define mix(a,b,c) \
{ \
  a -= b; a -= c; a ^= (c>>43); \
  b -= c; b -= a; b ^= (a<<9); \
  c -= a; c -= b; c ^= (b>>8); \
  a -= b; a -= c; a ^= (c>>38); \
  b -= c; b -= a; b ^= (a<<23); \
  c -= a; c -= b; c ^= (b>>5); \
  a -= b; a -= c; a ^= (c>>35); \
  b -= c; b -= a; b ^= (a<<49); \
  c -= a; c -= b; c ^= (b>>11); \
  a -= b; a -= c; a ^= (c>>12); \
  b -= c; b -= a; b ^= (a<<18); \
  c -= a; c -= b; c ^= (b>>22); \
}
#ifdef WORD_HASH                 /* alpha is little-endian, btw */
#define WORD_AT(ptr)    ( *(ulong *)(ptr) )
#else
#define WORD_AT(ptr)    ( (ptr)[0] + ((ulong)(ptr)[1]<<8) + \
			  ((ulong)(ptr)[2]<<16) + ((ulong)(ptr)[3]<<24) + \
			  ((ulong)(ptr)[4]<<32) + ((ulong)(ptr)[5]<<40) + \
			  ((ulong)(ptr)[6]<<48) + ((ulong)(ptr)[7]<<56) )
#endif

#else                            /* neither 32 or 64 bit words */
#error This hash function can only hash 32 or 64 bit words.  Sorry.
#endif

static ulong Hash(HashTable *ht, char *key, ulong cBuckets)
{
   ulong a, b, c, cchKey, cchKeyOrig;

   cchKeyOrig = ht->cchKey == NULL_TERMINATED ? strlen(key) : ht->cchKey;
   a = b = c = 0x9e3779b9;       /* the golden ratio; an arbitrary value */

   for ( cchKey = cchKeyOrig;  cchKey >= 3 * sizeof(ulong);
	 cchKey -= 3 * sizeof(ulong),  key += 3 * sizeof(ulong) )
   {
      a += WORD_AT(key);
      b += WORD_AT(key + sizeof(ulong));
      c += WORD_AT(key + sizeof(ulong)*2);
      mix(a,b,c);
   }

   c += cchKeyOrig;
   switch ( cchKey ) {           /* deal with rest.  Cases fall through */
#if LOG_WORD_SIZE == 5
      case 11: c += (ulong)key[10]<<24;
      case 10: c += (ulong)key[9]<<16;
      case 9 : c += (ulong)key[8]<<8;
               /* the first byte of c is reserved for the length */
      case 8 : b += WORD_AT(key+4);  a+= WORD_AT(key);  break;
      case 7 : b += (ulong)key[6]<<16;
      case 6 : b += (ulong)key[5]<<8;
      case 5 : b += key[4];
      case 4 : a += WORD_AT(key);  break;
      case 3 : a += (ulong)key[2]<<16;
      case 2 : a += (ulong)key[1]<<8;
      case 1 : a += key[0];
   /* case 0 : nothing left to add */
#elif LOG_WORD_SIZE == 6
      case 23: c += (ulong)key[22]<<56;
      case 22: c += (ulong)key[21]<<48;
      case 21: c += (ulong)key[20]<<40;
      case 20: c += (ulong)key[19]<<32;
      case 19: c += (ulong)key[18]<<24;
      case 18: c += (ulong)key[17]<<16;
      case 17: c += (ulong)key[16]<<8;
               /* the first byte of c is reserved for the length */
      case 16: b += WORD_AT(key+8);  a+= WORD_AT(key);  break;
      case 15: b += (ulong)key[14]<<48;
      case 14: b += (ulong)key[13]<<40;
      case 13: b += (ulong)key[12]<<32;
      case 12: b += (ulong)key[11]<<24;
      case 11: b += (ulong)key[10]<<16;
      case 10: b += (ulong)key[ 9]<<8;
      case  9: b += (ulong)key[ 8];
      case  8: a += WORD_AT(key);  break;
      case  7: a += (ulong)key[ 6]<<48;
      case  6: a += (ulong)key[ 5]<<40;
      case  5: a += (ulong)key[ 4]<<32;
      case  4: a += (ulong)key[ 3]<<24;
      case  3: a += (ulong)key[ 2]<<16;
      case  2: a += (ulong)key[ 1]<<8;
      case  1: a += (ulong)key[ 0];
   /* case 0: nothing left to add */
#endif
   }
   mix(a,b,c);
   return c & (cBuckets-1);
}


/*************************************************************************\
| Rehash()                                                                |
|     You give me a hashtable, a new size, and a bucket to follow, and    |
|     I resize the hashtable's bin to be the new size, rehashing          |
|     everything in it.  I keep particular track of the bucket you pass   |
|     in, and RETURN a pointer to where the item in the bucket got to.    |
|     (If you pass in NULL, I return an arbitrary pointer.)               |
\*************************************************************************/

static HTItem *Rehash(HashTable *ht, ulong cNewBuckets, HTItem *bckWatch)
{
   Table *tableNew;
   ulong iBucketFirst;
   HTItem *bck, *bckNew = NULL;
   ulong offset;                         /* the i in h(x) + i*(i-1)/2 */
   int fOverwrite = 0;    /* not an issue: there can be no collisions */

   assert( ht->table );
   cNewBuckets = Table(Allocate)(&tableNew, cNewBuckets);
      /* Since we RETURN the new position of bckWatch, we want  *
       * to make sure it doesn't get moved due to some table    *
       * rehashing that comes after it's inserted.  Thus, we    *
       * have to put it in last.  This makes the loop weird.    */
   for ( bck = HashFirstBucket(ht); ; bck = HashNextBucket(ht) )
   {
      if ( bck == NULL )      /* we're done iterating, so look at bckWatch */
      {
	 bck = bckWatch;
	 if ( bck == NULL )           /* I guess bckWatch wasn't specified */
	    break;
      }
      else if ( bck == bckWatch )
	 continue;             /* ignore if we see it during the iteration */

      offset = 0;                              /* a new i for a new bucket */
      for ( iBucketFirst = Hash(ht, KEY_PTR(ht, bck->key), cNewBuckets);
	    !Table(IsEmpty)(tableNew, iBucketFirst);
	    iBucketFirst = (iBucketFirst + JUMP(KEY_PTR(ht,bck->key), offset))
	                   & (cNewBuckets-1) )
	 ;
      bckNew = Table(Insert)(tableNew, bck, iBucketFirst, &fOverwrite);
      if ( bck == bckWatch )       /* we're done with the last thing to do */
	 break;
   }
   Table(Free)(ht->table, ht->cBuckets);
   ht->table = tableNew;
   ht->cBuckets = cNewBuckets;
   ht->cDeletedItems = 0;
   return bckNew;     /* new position of bckWatch, which was inserted last */
}

/*************************************************************************\
| Find()                                                                  |
|     Does the quadratic searching stuff.  RETURNS NULL if we don't       |
|     find an object with the given key, and a pointer to the Item        |
|     holding the key, if we do.  Also sets posLastFind.  If piEmpty is   |
|     non-NULL, we set it to the first open bucket we pass; helpful for   |
|     doing a later insert if the search fails, for instance.             |
\*************************************************************************/

static HTItem *Find(HashTable *ht, ulong key, ulong *piEmpty)
{
   ulong iBucketFirst;
   HTItem *item;
   ulong offset = 0;              /* the i in h(x) + i*(i-1)/2 */
   int fFoundEmpty = 0;           /* set when we pass over an empty bucket */

   ht->posLastFind = NULL;        /* set up for failure: a new find starts */
   if ( ht->table == NULL )       /* empty hash table: find is bound to fail */
      return NULL;

   iBucketFirst = Hash(ht, KEY_PTR(ht, key), ht->cBuckets);
   while ( 1 )                    /* now try all i > 0 */
   {
      item = Table(Find)(ht->table, iBucketFirst);
      if ( item == NULL )         /* it's not in the table */
      {
	 if ( piEmpty && !fFoundEmpty ) *piEmpty = iBucketFirst;
	 return NULL;
      }
      else
      {
	 if ( IS_BCK_DELETED(item) )      /* always 0 ifdef INSERT_ONLY */
	 {
	    if ( piEmpty && !fFoundEmpty )
	    {
	       *piEmpty = iBucketFirst;
	       fFoundEmpty = 1;
	    }
	 } else
	    if ( !KEY_CMP(ht, key, item->key) )     /* must be occupied */
	    {
	       ht->posLastFind = item;
	       return item;               /* we found it! */
	    }
      }
      iBucketFirst = ((iBucketFirst + JUMP(KEY_PTR(ht, key), offset))
		      & (ht->cBuckets-1));
   }
}

/*************************************************************************\
| Insert()                                                                |
|     If an item with the key already exists in the hashtable, RETURNS    |
|     a pointer to the item (replacing its data if fOverwrite is 1).      |
|     If not, we find the first place-to-insert (which Find() is nice     |
|     enough to set for us) and insert the item there, RETURNing a        |
|     pointer to the item.  We might grow the hashtable if it's getting   |
|     full.  Note we include buckets holding DELETED when determining     |
|     fullness, because they slow down searching.                         |
\*************************************************************************/

static ulong NextPow2(ulong x)    /* returns next power of 2 > x, or 2^31 */
{
   if ( ((x << 1) >> 1) != x )    /* next power of 2 overflows */
      x >>= 1;                    /* so we return highest power of 2 we can */
   while ( (x & (x-1)) != 0 )     /* blacks out all but the top bit */
      x &= (x-1);
   return x << 1;                 /* makes it the *next* power of 2 */
}

static HTItem *Insert(HashTable *ht, ulong key, ulong data, int fOverwrite)
{
   HTItem *item, bckInsert;
   ulong iEmpty;                  /* first empty bucket key probes */

   if ( ht->table == NULL )       /* empty hash table: find is bound to fail */
      return NULL;
   item = Find(ht, key, &iEmpty);
   ht->posLastFind = NULL;        /* last operation is insert, not find */
   if ( item )
   {
      if ( fOverwrite )
	 item->data = data;       /* key already matches */
      return item;
   }

   COPY_KEY(ht, bckInsert.key, key);    /* make our own copy of the key */
   bckInsert.data = data;               /* oh, and the data too */
   item = Table(Insert)(ht->table, &bckInsert, iEmpty, &fOverwrite);
   if ( fOverwrite )                    /* we overwrote a deleted bucket */
      ht->cDeletedItems--;
   ht->cItems++;                        /* insert couldn't have overwritten */
   if ( ht->cDeltaGoalSize > 0 )  /* closer to our goal size */
      ht->cDeltaGoalSize--;
   if ( ht->cItems + ht->cDeletedItems >= ht->cBuckets * OCCUPANCY_PCT
        || ht->cDeltaGoalSize < 0 ) /* we must've overestimated # of deletes */
      item = Rehash(ht, 
		    NextPow2((ulong)(((ht->cDeltaGoalSize > 0 ?
				       ht->cDeltaGoalSize : 0)
				      + ht->cItems) / OCCUPANCY_PCT)),
		    item);
   return item;
}

/*************************************************************************\
| Delete()                                                                |
|     Removes the item from the hashtable, and if fShrink is 1, will      |
|     shrink the hashtable if it's too small (ie even after halving,      |
|     the ht would be less than half full, though in order to avoid       |
|     oscillating table size, we insist that after halving the ht would   |
|     be less than 40% full).  RETURNS 1 if the item was found, 0 else.   |
|        If fLastFindSet is true, then this function is basically         |
|     DeleteLastFind.                                                     |
\*************************************************************************/

static int Delete(HashTable *ht, ulong key, int fShrink, int fLastFindSet)
{
   if ( !fLastFindSet && !Find(ht, key, NULL) )
      return 0;
   SET_BCK_DELETED(ht, ht->posLastFind);       /* find set this, how nice */
   ht->cItems--; 
   ht->cDeletedItems++;
   if ( ht->cDeltaGoalSize < 0 )  /* heading towards our goal of deletion */
      ht->cDeltaGoalSize++;

   if ( fShrink && ht->cItems < ht->cBuckets * OCCUPANCY_PCT*0.4 
        && ht->cDeltaGoalSize >= 0       /* wait until we're done deleting */
        && (ht->cBuckets >> 1) >= MIN_HASH_SIZE )                /* shrink */
      Rehash(ht,
	     NextPow2((ulong)((ht->cItems+ht->cDeltaGoalSize)/OCCUPANCY_PCT)),
	     NULL);
   ht->posLastFind = NULL;           /* last operation is delete, not find */
   return 1;
}


/* ======================================================================== */
/*                          USER-VISIBLE API                                */
/*                       ----------------------                             */

/*************************************************************************\
| AllocateHashTable()                                                     |
| ClearHashTable()                                                        |
| FreeHashTable()                                                         |
|     Allocate() allocates a hash table and sets up size parameters.      |
|     Free() frees it.  Clear() deletes all the items from the hash       |
|     table, but frees not.                                               |
|        cchKey is < 0 if the keys you send me are meant to be pointers   |
|     to \0-terminated strings.  Then -cchKey is the maximum key size.    |
|     If cchKey < one word (ulong), the keys you send me are the keys     |
|     themselves; else the keys you send me are pointers to the data.     |
|        If fSaveKeys is 1, we copy any keys given to us to insert.  We   |
|     also free these keys when freeing the hash table.  If it's 0, the   |
|     user is responsible for key space management.                       |
|        AllocateHashTable() RETURNS a hash table; the others TAKE one.   |
\*************************************************************************/

HashTable *AllocateHashTable(int cchKey, int fSaveKeys)
{
   HashTable *ht;

   ht = (HashTable *) HTsmalloc(sizeof(*ht));   /* set everything to 0 */
   ht->cBuckets = Table(Allocate)(&ht->table, MIN_HASH_SIZE);
   ht->cchKey = cchKey <= 0 ? NULL_TERMINATED : cchKey;
   ht->cItems = 0;
   ht->cDeletedItems = 0;
   ht->fSaveKeys = fSaveKeys;
   ht->cDeltaGoalSize = 0;
   ht->iter = HTsmalloc( sizeof(TableIterator) );

   ht->fpData = NULL;                           /* set by HashLoad, maybe */
   ht->bckData.data = (ulong) NULL;             /* this must be done */
   HTSetupKeyTrunc();                           /* in util.c */
   return ht;
}

void ClearHashTable(HashTable *ht)
{
   HTItem *bck;

   if ( STORES_PTR(ht) && ht->fSaveKeys )       /* need to free keys */
      for ( bck = HashFirstBucket(ht); bck; bck = HashNextBucket(ht) )
      {
	 FREE_KEY(ht, bck->key);
	 if ( ht->fSaveKeys == 2 )  /* this means key stored in one block */
	    break;                  /* ...so only free once */
      }
   Table(Free)(ht->table, ht->cBuckets);
   ht->cBuckets = Table(Allocate)(&ht->table, MIN_HASH_SIZE);

   ht->cItems = 0;
   ht->cDeletedItems = 0;
   ht->cDeltaGoalSize = 0;
   ht->posLastFind = NULL;
   ht->fpData = NULL;               /* no longer HashLoading */
   if ( ht->bckData.data )  free( (char *)(ht)->bckData.data);
   ht->bckData.data = (ulong) NULL;
}

void FreeHashTable(HashTable *ht)
{
   ClearHashTable(ht);
   if ( ht->iter )    HTfree(ht->iter, sizeof(TableIterator));
   if ( ht->table )   Table(Free)(ht->table, ht->cBuckets);
   free(ht);
}

/*************************************************************************\
| HashFind()                                                              |
| HashFindLast()                                                          |
|     HashFind(): looks in h(x) + i(i-1)/2 % t as i goes up from 0        |
|     until we either find the key or hit an empty bucket.  RETURNS a     |
|     pointer to the item in the hit bucket, if we find it, else          |
|     RETURNS NULL.                                                       |
|        HashFindLast() returns the item returned by the last             |
|     HashFind(), which may be NULL if the last HashFind() failed.        |
|        LOAD_AND_RETURN reads the data from off disk, if necessary.      |
\*************************************************************************/

HTItem *HashFind(HashTable *ht, ulong key)
{
   LOAD_AND_RETURN(ht, Find(ht, KEY_TRUNC(ht, key), NULL));
}

HTItem *HashFindLast(HashTable *ht)
{
   LOAD_AND_RETURN(ht, ht->posLastFind);
}

/*************************************************************************\
| HashFindOrInsert()                                                      |
| HashFindOrInsertItem()                                                  |
| HashInsert()                                                            |
| HashInsertItem()                                                        |
| HashDelete()                                                            |
| HashDeleteLast()                                                        |
|     Pretty obvious what these guys do.  Some take buckets (items),      |
|     some take keys and data separately.  All things RETURN the bucket   |
|     (a pointer into the hashtable) if appropriate.                      |
\*************************************************************************/

HTItem *HashFindOrInsert(HashTable *ht, ulong key, ulong dataInsert)
{
      /* This is equivalent to Insert without samekey-overwrite */
   return Insert(ht, KEY_TRUNC(ht, key), dataInsert, 0);
}

HTItem *HashFindOrInsertItem(HashTable *ht, HTItem *pItem)
{
   return HashFindOrInsert(ht, pItem->key, pItem->data);
}

HTItem *HashInsert(HashTable *ht, ulong key, ulong data)
{
   return Insert(ht, KEY_TRUNC(ht, key), data, SAMEKEY_OVERWRITE);
}

HTItem *HashInsertItem(HashTable *ht, HTItem *pItem)
{
   return HashInsert(ht, pItem->key, pItem->data);
}

int HashDelete(HashTable *ht, ulong key)
{
   return Delete(ht, KEY_TRUNC(ht, key), !FAST_DELETE, 0);
}

int HashDeleteLast(HashTable *ht)
{
   if ( !ht->posLastFind  )                /* last find failed */
      return 0;
   return Delete(ht, 0, !FAST_DELETE, 1);  /* no need to specify a key */
}

/*************************************************************************\
| HashFirstBucket()                                                       |
| HashNextBucket()                                                        |
|     Iterates through the items in the hashtable by iterating through    |
|     the table.  Since we know about deleted buckets and loading data    |
|     off disk, and the table doesn't, our job is to take care of these   |
|     things.  RETURNS a bucket, or NULL after the last bucket.           |
\*************************************************************************/

HTItem *HashFirstBucket(HashTable *ht)
{
   HTItem *retval;

   for ( retval = Table(FirstBucket)(ht->iter, ht->table, ht->cBuckets);
	 retval;  retval = Table(NextBucket)(ht->iter) )
      if ( !IS_BCK_DELETED(retval) )
	 LOAD_AND_RETURN(ht, retval);
   return NULL;
}

HTItem *HashNextBucket(HashTable *ht)
{
   HTItem *retval;

   while ( (retval=Table(NextBucket)(ht->iter)) )
      if ( !IS_BCK_DELETED(retval) )
	 LOAD_AND_RETURN(ht, retval);
   return NULL;
}

/*************************************************************************\
| HashSetDeltaGoalSize()                                                  |
|     If we're going to insert 100 items, set the delta goal size to      |
|     100 and we take that into account when inserting.  Likewise, if     |
|     we're going to delete 10 items, set it to -100 and we won't         |
|     rehash until all 100 have been done.  It's ok to be wrong, but      |
|     it's efficient to be right.  Returns the delta value.               |
\*************************************************************************/

int HashSetDeltaGoalSize(HashTable *ht, int delta)
{
   ht->cDeltaGoalSize = delta;
#if FAST_DELETE == 1 || defined INSERT_ONLY
   if ( ht->cDeltaGoalSize < 0 )   /* for fast delete, we never */
      ht->cDeltaGoalSize = 0;      /* ...rehash after deletion  */
#endif
   return ht->cDeltaGoalSize;
}


/*************************************************************************\
| HashSave()                                                              |
| HashLoad()                                                              |
| HashLoadKeys()                                                          |
|     Routines for saving and loading the hashtable from disk.  We can    |
|     then use the hashtable in two ways: loading it back into memory     |
|     (HashLoad()) or loading only the keys into memory, in which case    |
|     the data for a given key is loaded off disk when the key is         |
|     retrieved.  The data is freed when something new is retrieved in    |
|     its place, so this is not a "lazy-load" scheme.                     |
|        The key is saved automatically and restored upon load, but the   |
|     user needs to specify a routine for reading and writing the data.   |
|     fSaveKeys is of course set to 1 when you read in a hashtable.       |
|     HashLoad RETURNS a newly allocated hashtable.                       |
|        DATA_WRITE() takes an fp and a char * (representing the data     |
|     field), and must perform two separate tasks.  If fp is NULL,        |
|     return the number of bytes written.  If not, writes the data to     |
|     disk at the place the fp points to.                                 |
|        DATA_READ() takes an fp and the number of bytes in the data      |
|     field, and returns a char * which points to wherever you've         |
|     written the data.  Thus, you must allocate memory for the data.     |
|        Both dataRead and dataWrite may be NULL if you just wish to      |
|     store the data field directly, as an integer.                       |
\*************************************************************************/

void HashSave(FILE *fp, HashTable *ht, int (*dataWrite)(FILE *, char *))
{
   long cchData, posStart;
   HTItem *bck;

   /* File format: magic number (4 bytes)
                 : cchKey (one word)
                 : cItems (one word)
                 : cDeletedItems (one word)
                 : table info (buckets and a bitmap)
		 : cchAllKeys (one word)
      Then the keys, in a block.  If cchKey is NULL_TERMINATED, the keys
      are null-terminated too, otherwise this takes up cchKey*cItems bytes.
      Note that keys are not written for DELETED buckets.
      Then the data:
                 : EITHER DELETED (one word) to indicate it's a deleted bucket,
                 : OR number of bytes for this (non-empty) bucket's data
                   (one word).  This is not stored if dataWrite == NULL
                   since the size is known to be sizeof(ul).  Plus:
                 : the data for this bucket (variable length)
      All words are in network byte order. */

   fprintf(fp, "%s", MAGIC_KEY);
   WRITE_UL(fp, ht->cchKey);        /* WRITE_UL, READ_UL, etc in fks-hash.h */
   WRITE_UL(fp, ht->cItems);
   WRITE_UL(fp, ht->cDeletedItems);
   Table(Write)(fp, ht->table, ht->cBuckets);        /* writes cBuckets too */

   WRITE_UL(fp, 0);                 /* to be replaced with sizeof(key block) */
   posStart = ftell(fp);
   for ( bck = HashFirstBucket(ht); bck; bck = HashNextBucket(ht) )
      fwrite(KEY_PTR(ht, bck->key), 1,
	     (ht->cchKey == NULL_TERMINATED ?
	      strlen(KEY_PTR(ht, bck->key))+1 : ht->cchKey), fp);
   cchData = ftell(fp) - posStart;
   fseek(fp, posStart - sizeof(unsigned long), SEEK_SET);
   WRITE_UL(fp, cchData);
   fseek(fp, 0, SEEK_END);          /* done with our sojourn at the header */

      /* Unlike HashFirstBucket, TableFirstBucket iters through deleted bcks */
   for ( bck = Table(FirstBucket)(ht->iter, ht->table, ht->cBuckets);
	 bck;  bck = Table(NextBucket)(ht->iter) )
      if ( dataWrite == NULL || IS_BCK_DELETED(bck) )
	 WRITE_UL(fp, bck->data);
      else                          /* write cchData followed by the data */
      {
	 WRITE_UL(fp, (*dataWrite)(NULL, (char *)bck->data));
	 (*dataWrite)(fp, (char *)bck->data);
      }
}

static HashTable *HashDoLoad(FILE *fp, char * (*dataRead)(FILE *, int), 
			     HashTable *ht)
{
   ulong cchKey;
   char szMagicKey[4], *rgchKeys;
   HTItem *bck;

   fread(szMagicKey, 1, 4, fp);
   if ( strncmp(szMagicKey, MAGIC_KEY, 4) )
   {
      fprintf(stderr, "ERROR: not a hash table (magic key is %4.4s, not %s)\n",
	      szMagicKey, MAGIC_KEY);
      exit(3);
   }
   Table(Free)(ht->table, ht->cBuckets);  /* allocated in AllocateHashTable */

   READ_UL(fp, ht->cchKey);
   READ_UL(fp, ht->cItems);
   READ_UL(fp, ht->cDeletedItems);
   ht->cBuckets = Table(Read)(fp, &ht->table);    /* next is the table info */

   READ_UL(fp, cchKey);
   rgchKeys = (char *) HTsmalloc( cchKey );  /* stores all the keys */
   fread(rgchKeys, 1, cchKey, fp);
      /* We use the table iterator so we don't try to LOAD_AND_RETURN */
   for ( bck = Table(FirstBucket)(ht->iter, ht->table, ht->cBuckets);
	 bck;  bck = Table(NextBucket)(ht->iter) )
   {
      READ_UL(fp, bck->data);        /* all we need if dataRead is NULL */
      if ( IS_BCK_DELETED(bck) )     /* always 0 if defined(INSERT_ONLY) */
	 continue;                   /* this is why we read the data first */
      if ( dataRead != NULL )        /* if it's null, we're done */
	 if ( !ht->fpData )          /* load data into memory */
	    bck->data = (ulong)dataRead(fp, bck->data);
	 else                        /* store location of data on disk */
	 {
	    fseek(fp, bck->data, SEEK_CUR);  /* bck->data held size of data */
	    bck->data = ftell(fp) - bck->data - sizeof(unsigned long);
	 }

      if ( ht->cchKey == NULL_TERMINATED )   /* now read the key */
      {
	 bck->key = (ulong) rgchKeys;
	 rgchKeys = strchr(rgchKeys, '\0') + 1;    /* read past the string */
      }
      else
      {
	 if ( STORES_PTR(ht) )               /* small keys stored directly */
	    bck->key = (ulong) rgchKeys;
	 else
	    memcpy(&bck->key, rgchKeys, ht->cchKey);
	 rgchKeys += ht->cchKey;
      }
   }
   if ( !STORES_PTR(ht) )                    /* keys are stored directly */
      HTfree(rgchKeys - cchKey, cchKey);     /* we've advanced rgchK to end */
   return ht;
}

HashTable *HashLoad(FILE *fp, char * (*dataRead)(FILE *, int))
{
   HashTable *ht;
   ht = AllocateHashTable(0, 2);  /* cchKey set later, fSaveKey should be 2! */
   return HashDoLoad(fp, dataRead, ht);
}

HashTable *HashLoadKeys(FILE *fp, char * (*dataRead)(FILE *, int))
{
   HashTable *ht;

   if ( dataRead == NULL )
      return HashLoad(fp, NULL);  /* no reason not to load the data here */
   ht = AllocateHashTable(0, 2);  /* cchKey set later, fSaveKey should be 2! */
   ht->fpData = fp;               /* tells HashDoLoad() to only load keys */
   ht->dataRead = dataRead;
   return HashDoLoad(fp, dataRead, ht);
}

/*************************************************************************\
| PrintHashTable()                                                        |
|     A debugging tool.  Prints the entire contents of the hash table,    |
|     like so: <bin #>: key of the contents.  Returns number of bytes     |
|     allocated.  If time is not -1, we print it as the time required     |
|     for the hash.  If iForm is 0, we just print the stats.  If it's     |
|     1, we print the keys and data too, but the keys are printed as      |
|     ulongs.  If it's 2, we print the keys correctly (as long numbers    |
|     or as strings).                                                     |
\*************************************************************************/

ulong PrintHashTable(HashTable *ht, double time, int iForm)
{
   ulong cbData = 0, cbBin = 0, cItems = 0, cOccupied = 0;
   HTItem *item;

   printf("HASH TABLE.\n");
   if ( time > -1.0 )
   {
      printf("----------\n");
      printf("Time: %27.2f\n", time);
   }

   for ( item = Table(FirstBucket)(ht->iter, ht->table, ht->cBuckets);
	 item;  item = Table(NextBucket)(ht->iter) )
   {
      cOccupied++;                    /* this includes deleted buckets */
      if ( IS_BCK_DELETED(item) )     /* we don't need you for anything else */
	 continue;
      cItems++;                       /* this is for a sanity check */
      if ( STORES_PTR(ht) )
	 cbData += ht->cchKey == NULL_TERMINATED ? 
	    WORD_ROUND(strlen((char *)item->key)+1) : ht->cchKey;
      else
	 cbBin -= sizeof(item->key), cbData += sizeof(item->key);
      cbBin -= sizeof(item->data), cbData += sizeof(item->data);
      if ( iForm != 0 )      /* we want the actual contents */
      {
	 if ( iForm == 2 && ht->cchKey == NULL_TERMINATED ) 
	    printf("%s/%lu\n", (char *)item->key, item->data);
	 else if ( iForm == 2 && STORES_PTR(ht) )
	    printf("%.*s/%lu\n", 
		   (int)ht->cchKey, (char *)item->key, item->data);
	 else     /* either key actually is a ulong, or iForm == 1 */
	    printf("%lu/%lu\n", item->key, item->data);
      }
   }
   assert( cItems == ht->cItems );                   /* sanity check */
   cbBin = Table(Memory)(ht->cBuckets, cOccupied);

   printf("----------\n");   
   printf("%lu buckets (%lu bytes).  %lu empty.  %lu hold deleted items.\n"
	  "%lu items (%lu bytes).\n"
	  "%lu bytes total.  %lu bytes (%2.1f%%) of this is ht overhead.\n",
	  ht->cBuckets, cbBin, ht->cBuckets - cOccupied, cOccupied - ht->cItems,
	  ht->cItems, cbData,
	  cbData + cbBin, cbBin, cbBin*100.0/(cbBin+cbData));

   return cbData + cbBin;
}
