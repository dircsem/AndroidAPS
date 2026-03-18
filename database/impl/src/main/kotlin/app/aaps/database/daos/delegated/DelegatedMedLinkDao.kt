package app.aaps.database.daos.delegated


import app.aaps.database.daos.MedLinkDao
import app.aaps.database.entities.MedLinkConfig
import app.aaps.database.entities.interfaces.DBEntry

internal class DelegatedMedLinkDao(changes: MutableList<DBEntry>, private val dao: MedLinkDao) : DelegatedDao(changes), MedLinkDao by dao {

    override fun insertNewEntry(entry: MedLinkConfig): Long {
        changes.add(entry)
        return dao.insertNewEntry(entry)
    }

    override fun updateExistingEntry(entry: MedLinkConfig): Long {
        changes.add(entry)
        return dao.updateExistingEntry(entry)
    }
}