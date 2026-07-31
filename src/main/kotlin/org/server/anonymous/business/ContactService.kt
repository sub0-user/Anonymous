package org.server.anonymous.business

import org.server.anonymous.business.model.Contact

interface ContactService {
    fun listContacts(): List<Contact>

    fun addContact(
        alias: String,
        address: String,
    ): OpResult<Contact>
}
