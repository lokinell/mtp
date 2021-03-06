package com.vedri.mtp.core.support.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.vedri.mtp.core.MtpConstants;

public class CassandraPartitionFetcher {

	private PreparedStatement loadAllStatement;
	private PreparedStatement loadAllStatementBoundary;

	private Session session;
	private String indexByValue;
	private int fetchSize;
	private DateTime from;
	private DateTime to;
	private String currentPartition;
	private UUID lastToken;
	private int partitionMoveCount;
	private String terminalPartition;
	private float okPartitionDiff;

	private static final int MAX_PARTITION_MOVE_COUNT = 8800;

	public CassandraPartitionFetcher(Session session, String indexByValue,
			PreparedStatement loadAllStatement,
			PreparedStatement loadAllStatementBoundary,
			PreparedStatement loadAllStatementInvert,
			PreparedStatement loadAllStatementBoundaryInvert,
			int fetchSize, DateTime from, DateTime to) {
		this.session = session;

		this.indexByValue = indexByValue;

		if (from == null || to == null) {
			from = new DateTime(MtpConstants.DEFAULT_TIME_ZONE);
			to = new DateTime(MtpConstants.DEFAULT_TIME_ZONE).minusHours(1);
		}

		this.fetchSize = fetchSize;

		this.from = from;
		this.to = to;

		this.currentPartition = CassandraPartitionForHourUtils.datePartition(from);
		this.terminalPartition = CassandraPartitionForHourUtils.datePartition(to);

		this.lastToken = null;

		this.partitionMoveCount = 0;

		if (!isInvert()) {
			this.loadAllStatement = loadAllStatement;
			this.loadAllStatementBoundary = loadAllStatementBoundary;

			okPartitionDiff = -1f;
		}
		else {
			this.loadAllStatement = loadAllStatementInvert;
			this.loadAllStatementBoundary = loadAllStatementBoundaryInvert;

			this.okPartitionDiff = 1f;
		}
	}

	public boolean isInvert() {
		return !from.isBefore(to);
	}

	public DateTime getStart() {
		if (isInvert()) {
			return to;
		}
		else {
			return from;
		}
	}

	public DateTime getEnd() {
		if (isInvert()) {
			return from;
		}
		else {
			return to;
		}
	}

	public List<Row> getNextPage() {

		List<Row> resultRows = new ArrayList<>();

		boolean bindWithPrevious = false;

		do {
			final BoundStatement bs = lastToken == null ? loadAllStatement.bind(currentPartition)
					: loadAllStatementBoundary.bind(currentPartition, lastToken);

			List<Row> result = session.execute(bs).all();

			if (result.size() == fetchSize) {
				if (bindWithPrevious) {
					resultRows.addAll(result.subList(0, fetchSize - resultRows.size()));
				}
				else {
					resultRows = result;
				}

				lastToken = resultRows.get(resultRows.size() - 1).getUUID(indexByValue);

			}
			else if (result.size() == 0) {
				moveToNextPartition();

			}
			else if (result.size() < fetchSize) {

				if (bindWithPrevious) {
					final int length = Math.min(result.size(), fetchSize - resultRows.size());
					resultRows.addAll(result.subList(0, length));

					if (length == result.size()) {
						moveToNextPartition();
						lastToken = null;
					} else {
						lastToken = resultRows.get(resultRows.size() - 1).getUUID(indexByValue);
					}
				}
				else {
					resultRows = result;
					moveToNextPartition();
					lastToken = null;
				}

				bindWithPrevious = true;
			}

		} while (resultRows.size() < fetchSize && hasNextPartition());

		return resultRows;
	}

	public boolean hasNextPartition() {
		final float compareResult = Math.signum(currentPartition.compareTo(terminalPartition));

		return partitionMoveCount < MAX_PARTITION_MOVE_COUNT
				&& (compareResult == okPartitionDiff || compareResult == 0);
	}

	private void moveToNextPartition() {
		partitionMoveCount++;

		currentPartition = CassandraPartitionForHourUtils.offsetPartitionIntoHistoryBy(currentPartition,
				from.isBefore(to) ? -1 : 1);
	}
}
