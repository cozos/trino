==============================
SingleStore (MemSQL) connector
==============================

The SingleStore (formerly known as MemSQL) connector allows querying and
creating tables in an external SingleStore database. The SingleStore connector
is very similar to the MySQL connector with the only difference being the
underlying driver.

Requirements
------------

To connect to SingleStore, you need:

* SingleStore version 7.3 or higher.
* Network access from the Trino coordinator and workers to SingleStore. Port
  3306 is the default port.

Configuration
-------------

To configure the SingleStore connector, create a catalog properties file
in ``etc/catalog`` named, for example, ``singlestore.properties``, to
mount the SingleStore connector as the ``singlestore`` catalog.
Create the file with the following contents, replacing the
connection properties as appropriate for your setup:

.. code-block:: text

    connector.name=memsql
    connection-url=jdbc:mariadb://example.net:3306
    connection-user=root
    connection-password=secret

Multiple SingleStore servers
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can have as many catalogs as you need, so if you have additional
SingleStore servers, simply add another properties file to ``etc/catalog``
with a different name (making sure it ends in ``.properties``). For
example, if you name the property file ``sales.properties``, Trino
will create a catalog named ``sales`` using the configured connector.

.. include:: jdbc-common-configurations.fragment

.. include:: jdbc-case-insensitive-matching.fragment

.. include:: non-transactional-insert.fragment

Querying SingleStore
--------------------

The SingleStore connector provides a schema for every SingleStore *database*.
You can see the available SingleStore databases by running ``SHOW SCHEMAS``::

    SHOW SCHEMAS FROM memsql;

If you have a SingleStore database named ``web``, you can view the tables
in this database by running ``SHOW TABLES``::

    SHOW TABLES FROM memsql.web;

You can see a list of the columns in the ``clicks`` table in the ``web``
database using either of the following::

    DESCRIBE memsql.web.clicks;
    SHOW COLUMNS FROM memsql.web.clicks;

Finally, you can access the ``clicks`` table in the ``web`` database::

    SELECT * FROM memsql.web.clicks;

If you used a different name for your catalog properties file, use
that catalog name instead of ``memsql`` in the above examples.

.. _memsql-type-mapping:

Type mapping
------------

.. include:: jdbc-type-mapping.fragment

.. _memsql-pushdown:

Pushdown
--------

The connector supports pushdown for a number of operations:

* :ref:`join-pushdown`
* :ref:`limit-pushdown`
* :ref:`topn-pushdown`

.. _singlestore-sql-support:

SQL support
-----------

The connector provides read access and write access to data and metadata in
a SingleStore database.  In addition to the :ref:`globally available
<sql-globally-available>` and :ref:`read operation <sql-read-operations>`
statements, the connector supports the following features:

* :doc:`/sql/insert`
* :doc:`/sql/delete`
* :doc:`/sql/truncate`
* :doc:`/sql/create-table`
* :doc:`/sql/create-table-as`
* :doc:`/sql/drop-table`
* :doc:`/sql/alter-table`
* :doc:`/sql/create-schema`
* :doc:`/sql/drop-schema`

.. include:: sql-delete-limitation.fragment

.. include:: alter-table-limitation.fragment
