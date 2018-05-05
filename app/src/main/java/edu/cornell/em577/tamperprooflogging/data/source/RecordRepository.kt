package edu.cornell.em577.tamperprooflogging.data.source

import android.content.Context
import android.content.res.Resources
import edu.cornell.em577.tamperprooflogging.data.model.Transaction
import edu.cornell.em577.tamperprooflogging.util.GrowOnlySet
import edu.cornell.em577.tamperprooflogging.util.SingletonHolder

/** Repository holding all recorded record accesses. */
class RecordRepository private constructor(env: Pair<Context, Resources>) {

    companion object : SingletonHolder<RecordRepository, Pair<Context, Resources>>(::RecordRepository)

    private val recordAccesses = GrowOnlySet<Transaction>()

    /** Add a record access to this repository. */
    fun addRecordAccess(recordAccess: Transaction) {
        synchronized(recordAccesses) {
            recordAccesses.add(recordAccess)
        }
    }

    /** Retrieve all recorded record accesses known to this repository. */
    fun getAllRecordAccesses(): List<Transaction> {
        synchronized(recordAccesses) {
            return recordAccesses.toList()
        }
    }
}