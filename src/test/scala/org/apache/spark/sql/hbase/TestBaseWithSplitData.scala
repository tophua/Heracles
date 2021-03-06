/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hbase

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.util.concurrent.Executors

import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.GenericRow
import org.apache.spark.sql.hbase.util.{BinaryBytesUtils, DataTypeUtils, HBaseKVHelper}
import org.apache.spark.sql.types._

/**
 * HBaseMainTest
 * create HbTestTable and metadata table, and insert some data
  * 创建HbTestTable和元数据表,并插入一些数据
 */
class TestBaseWithSplitData extends TestBase {
  val namespace = "default"
  val TableName_a: String = "ta"
  val TableName_b: String = "tb"
  val HbaseTableName = TableName.valueOf("ht")
  val Metadata_Table = TableName.valueOf("metadata")
  var alreadyInserted = false

  override protected def beforeAll() = {
    super.beforeAll()
    //
    setupData(useMultiplePartitions = true, needInsertData = true)
    TestData
  }

  override protected def afterAll() = {
    runSql("DROP TABLE " + TableName_a)
    runSql("DROP TABLE " + TableName_b)
    dropNativeHbaseTable("ht")
    super.afterAll()
  }
  //使用多个分区
  def createTable(useMultiplePartitions: Boolean) = {
    // delete the existing hbase table
    //删除现有的hbase表
    if (TestHbase.hbaseAdmin.tableExists(HbaseTableName)) {
      TestHbase.hbaseAdmin.disableTable(HbaseTableName)
      TestHbase.hbaseAdmin.deleteTable(HbaseTableName)
    }
    if (TestHbase.hbaseAdmin.tableExists(Metadata_Table)) {
      TestHbase.hbaseAdmin.disableTable(Metadata_Table)
      TestHbase.hbaseAdmin.deleteTable(Metadata_Table)
    }

    var allColumns = List[AbstractColumn]()
    allColumns = allColumns :+ KeyColumn("col1", StringType, 1)
    allColumns = allColumns :+ NonKeyColumn("col2", ByteType, "cf1", "cq11")
    allColumns = allColumns :+ KeyColumn("col3", ShortType, 2)
    allColumns = allColumns :+ NonKeyColumn("col4", IntegerType, "cf1", "cq12")
    allColumns = allColumns :+ NonKeyColumn("col5", LongType, "cf2", "cq21")
    allColumns = allColumns :+ NonKeyColumn("col6", FloatType, "cf2", "cq22")
    allColumns = allColumns :+ KeyColumn("col7", IntegerType, 0)

    val splitKeys: Array[Array[Byte]] = if (useMultiplePartitions) {
      Array(
        new GenericRow(Array(256, " p256 ", 128: Short)),
        new GenericRow(Array(32, " p32 ", 256: Short)),
        new GenericRow(Array(-32, " n32 ", 128: Short)),
        new GenericRow(Array(-256, " n256 ", 256: Short)),
        new GenericRow(Array(-128, " n128 ", 128: Short)),
        new GenericRow(Array(0, " zero ", 256: Short)),
        new GenericRow(Array(128, " p128 ", 512: Short))
      ).map(HBaseKVHelper.makeRowKey(_, Seq(IntegerType, StringType, ShortType)))
    } else {
      null
    }

    TestHbase.sharedState.externalCatalog.asInstanceOf[HBaseCatalog].createTable(
      TableName_a, namespace, HbaseTableName.getNameAsString, allColumns, splitKeys)

    runSql(s"""CREATE TABLE $TableName_b (col1 STRING, col2 BYTE, col3 SHORT, col4 INT, col5 LONG, col6 FLOAT, col7 INT) TBLPROPERTIES(
                'hbaseTableName'='$HbaseTableName',
                'keyCols'='col7;col1;col3',
                'nonKeyCols'='col2,cf1,cq11;col4,cf1,cq12;col5,cf2,cq21;col6,cf2,cq21')""".stripMargin)

    if (!TestHbase.hbaseAdmin.tableExists(HbaseTableName)) {
      throw new IllegalArgumentException("where is our table?")
    }
  }

  def checkHBaseTableExists(hbaseTable: TableName): Boolean = {
    TestHbase.hbaseAdmin.tableExists(hbaseTable)
  }

  def insertTestData() = {
    if (!checkHBaseTableExists(HbaseTableName)) {
      throw new IllegalStateException(s"Unable to find table $HbaseTableName")
    }

    def putNewTableIntoHBase(keys: Seq[Any], keysType: Seq[DataType],
                             vals: Seq[Any], valsType: Seq[DataType]): Unit = {
      val row = new GenericRow(keys.toArray)
      val key = makeRowKey(row, keysType)
      val put = new Put(key)
      Seq((vals.head, valsType.head, "cf1", "cq11"),
        (vals(1), valsType(1), "cf1", "cq12"),
        (vals(2), valsType(2), "cf2", "cq21"),
        (vals(3), valsType(3), "cf2", "cq22")).foreach {
        case (rowValue, rowType, colFamily, colQualifier) =>
          addRowVals(put, rowValue, rowType, colFamily, colQualifier)
      }
      val executor = Executors.newFixedThreadPool(10)
      val connection = ConnectionFactory.createConnection(
        TestHbase.sparkContext.hadoopConfiguration, executor)
      val table = connection.getTable(HbaseTableName)
      try {
        table.put(put)
      } finally {
        table.close()
        connection.close()
      }
    }

    putNewTableIntoHBase(Seq(-257, " n257 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](1.toByte, -2048, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(-255, " n255 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](2.toByte, -1024, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(-129, " n129 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](3.toByte, -512, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(-127, " n127 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](4.toByte, -256, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(-33, " n33 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](5.toByte, -128, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(-31, " n31 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](6.toByte, -64, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(-1, " n1 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](7.toByte, -1, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(1, " p1 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](8.toByte, 1, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(31, " p31 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](9.toByte, 4, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(33, " p33 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](10.toByte, 64, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(127, " p127 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](11.toByte, 128, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(129, " p129 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](12.toByte, 256, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(255, " p255 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](13.toByte, 512, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))

    putNewTableIntoHBase(Seq(257, " p257 ", 128: Short),
      Seq(IntegerType, StringType, ShortType),
      Seq[Any](14.toByte, 1024, 12345678901234L, 1234.5678F),
      Seq(ByteType, IntegerType, LongType, FloatType))
  }

  def makeRowKey(row: Row, dataTypeOfKeys: Seq[DataType]) = {
    val rawKeyCol = dataTypeOfKeys.zipWithIndex.map {
      case (dataType, index) =>
        (DataTypeUtils.getRowColumnInHBaseRawType(row, index, dataType),
          dataType)
    }

    HBaseKVHelper.encodingRawKeyColumns(rawKeyCol)
  }

  def addRowVals(put: Put, rowValue: Any, rowType: DataType,
                 colFamily: String, colQualifier: String) = {
    val bos = new ByteArrayOutputStream()
    val dos = new DataOutputStream(bos)
    val bu = BinaryBytesUtils.create(rowType)
    rowType match {
      case StringType => dos.write(bu.toBytes(rowValue.asInstanceOf[String]))
      case IntegerType => dos.write(bu.toBytes(rowValue.asInstanceOf[Int]))
      case BooleanType => dos.write(bu.toBytes(rowValue.asInstanceOf[Boolean]))
      case ByteType => dos.write(bu.toBytes(rowValue.asInstanceOf[Byte]))
      case DoubleType => dos.write(bu.toBytes(rowValue.asInstanceOf[Double]))
      case FloatType => dos.write(bu.toBytes(rowValue.asInstanceOf[Float]))
      case LongType => dos.write(bu.toBytes(rowValue.asInstanceOf[Long]))
      case ShortType => dos.write(bu.toBytes(rowValue.asInstanceOf[Short]))
      case _ => throw new Exception("Unsupported HBase SQL Data Type")
    }
    put.addImmutable(Bytes.toBytes(colFamily), Bytes.toBytes(colQualifier), bos.toByteArray)
  }

  def setupData(useMultiplePartitions: Boolean, needInsertData: Boolean = false) {
    if (needInsertData && !alreadyInserted) {
      createTable(useMultiplePartitions)
      insertTestData()
      alreadyInserted = true
    }
  }
}
