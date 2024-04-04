package app.aaps.database.impl.daos.delegated

import app.aaps.database.impl.daos.MedLinkDao
import app.aaps.database.entities.MedLinkConfig
import app.aaps.database.entities.interfaces.DBEntry

internal class DelegatedMedLinkDao(changes: MutableList<DBEntry>, private val dao: MedLinkDao) : DelegatedDao(changes), MedLinkDao by dao {

    override fun insertNewEntry(medLinkConfig: MedLinkConfig): Long {
        changes.add(medLinkConfig)
        return dao.insertNewEntry(medLinkConfig)
    }

    override fun updateExistingEntry(medLinkConfig: MedLinkConfig): Long {
        changes.add(medLinkConfig)
        return dao.updateExistingEntry(medLinkConfig)
    }
}