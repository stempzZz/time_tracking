package com.tracking.dao.mysql;

import com.tracking.dao.ActivityDAO;
import com.tracking.dao.DAOFactory;
import com.tracking.dao.RequestDAO;
import com.tracking.controllers.exceptions.DBException;
import com.tracking.dao.mapper.EntityMapper;
import com.tracking.models.Activity;
import com.tracking.models.Request;
import com.tracking.models.User;
import org.apache.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import java.sql.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.tracking.dao.mysql.MysqlConstants.*;

/**
 * MySQL Request DAO. Here is realized all methods of Request DAO for MySQL DB
 */
public class MysqlRequestDAO implements RequestDAO {

    private static final Logger logger = Logger.getLogger(MysqlRequestDAO.class);

    private final DAOFactory factory = MysqlDAOFactory.getInstance();

    private static RequestDAO instance;

    protected static synchronized RequestDAO getInstance() {
        if (instance == null)
            instance = new MysqlRequestDAO();
        return instance;
    }

    private MysqlRequestDAO() {

    }

    @Override
    public synchronized Request create(Activity activity, boolean forDelete) throws DBException {

        Connection con = null;
        try {
            con = factory.getConnection();
            con.setAutoCommit(false);
            if (forDelete)
                updateActivityStatus(con, activity, Activity.Status.DEL_WAITING);
            else
                createActivity(con, activity);

            Request request = null;
            try (PreparedStatement prst = con.prepareStatement(INSERT_REQUEST, Statement.RETURN_GENERATED_KEYS)) {
                int c = 0;
                prst.setInt(++c, activity.getId());
                if (forDelete) {
                    prst.setBoolean(++c, forDelete);
                    prst.execute();
                    try (ResultSet rs = prst.getGeneratedKeys()) {
                        if (rs.next())
                            request = new Request(rs.getInt(1), activity.getId(), Request.Status.WAITING,
                                    true, activity);
                    }
                } else {
                    prst.setNull(++c, Types.NULL);
                    prst.execute();
                    try (ResultSet rs = prst.getGeneratedKeys()) {
                        if (rs.next())
                            request = new Request(rs.getInt(1), Request.Status.WAITING,
                                    false, activity);
                    }
                }
                if (forDelete)
                    logger.info("Request for remove was created successfully");
                else
                    logger.info("Request for add was created successfully");
            }

            con.commit();
            return request;
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            factory.rollback(con);
            throw new DBException("MysqlRequestDAO: create was failed.", e);
        } finally {
            factory.closeResource(con);
        }
    }

    @Override
    public List<Request> getAll(String sort, String order, int start, int total, User authUser) throws DBException {
        String orderBy, query;
        if (sort != null && !sort.isEmpty()) {
            sort = "requests." + sort;
            orderBy = sort + " " + order;
            query = authUser.isAdmin() ? SELECT_REQUESTS_ORDER.replace(ORDER_BY, orderBy) :
                    SELECT_USER_REQUESTS_ORDER.replace(ORDER_BY, orderBy);
        } else if (order.equals("desc")){
            query = authUser.isAdmin() ? SELECT_REQUESTS_REVERSE : SELECT_USER_REQUESTS_REVERSE;
        } else {
            query = authUser.isAdmin() ? SELECT_REQUESTS : SELECT_USER_REQUESTS;       // user selection
        }

        try (Connection con = factory.getConnection();
             PreparedStatement prst = con.prepareStatement(query)) {
            List<Request> requestList = new ArrayList<>();
            int c = 0;
            if (!authUser.isAdmin())
                prst.setInt(++c, authUser.getId());
            prst.setInt(++c, start - 1);
            prst.setInt(++c, total);
            RequestMapper mapper = new RequestMapper();
            try (ResultSet rs = prst.executeQuery()) {
                while (rs.next()) {
                    Request request = mapper.mapRow(rs);
                    requestList.add(request);
                }
            }
            logger.info("Selection of requests was successful");
            logger.info("Count of request " + requestList.size());
            return requestList;
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            throw new DBException("MysqlRequestDAO: getAll was failed.", e);
        }
    }

    @Override
    public Request get(int requestId) throws DBException {
        try (Connection con = factory.getConnection();
             PreparedStatement prst = con.prepareStatement(SELECT_REQUEST)) {
            Request request = null;
            int c = 0;
            prst.setInt(++c, requestId);
            RequestMapper mapper = new RequestMapper();
            try (ResultSet rs = prst.executeQuery()) {
                if (rs.next()) {
                    request = mapper.mapActivity(rs);
                    request.getActivity().setCategories(withCategories(con, request.getActivityId()));
                }
            }
            logger.info("Selection of request (id=" + requestId + ") was successful");
            return request;
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            throw new DBException("MysqlRequestDAO: get was failed.", e);
        }
    }

    @Override
    public int getCount(User authUser) throws DBException {
        String query = authUser.isAdmin() ? GET_REQUEST_COUNT : GET_USER_REQUEST_COUNT;
        try (Connection con = factory.getConnection();
             PreparedStatement prst = con.prepareStatement(query)) {
            int c = 0;
            if (!authUser.isAdmin())
                prst.setInt(++c, authUser.getId());
            try (ResultSet rs = prst.executeQuery()) {
                int count = 0;
                if (rs.next())
                    count = rs.getInt(COL_COUNT);
                logger.info("Count selection of requests was successful");
                return count;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            throw new DBException("MysqlRequestDAO: getCount was failed.", e);
        }
    }

    @Override
    public void createActivity(Connection con, Activity activity) throws DBException {
        try (PreparedStatement prst = con.prepareStatement(INSERT_ACTIVITY, Statement.RETURN_GENERATED_KEYS)) {
            int c = 0;
            prst.setString(++c, activity.getName());
            prst.setString(++c, activity.getDescription());
            if (activity.getImage() == null || activity.getImage().isEmpty())
                prst.setNull(++c, Types.NULL);
            else
                prst.setString(++c, activity.getImage());
            prst.setInt(++c, activity.getCreatorId());
            prst.setString(++c, activity.getStatus().getValue());
            prst.execute();
            int activityId = getInsertedActivityId(prst);
            activity.setId(activityId);
            setActivityCategories(con, activity.getCategories(), activityId);
            logger.info("Activity was created successfully");
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            throw new DBException("MysqlRequestDAO: createActivity was failed.", e);
        }
    }

    @Override
    public void setActivityCategories(Connection con, List<Integer> categoryIds, int activityId) throws DBException {
        if (categoryIds == null || categoryIds.isEmpty())
            return;
        try (PreparedStatement prst = con.prepareStatement(INSERT_ACTIVITY_CATEGORIES)) {
            for (int categoryId : categoryIds) {
                int c = 0;
                prst.setInt(++c, activityId);
                prst.setInt(++c, categoryId);
                prst.execute();
                logger.info("Category (id=" + categoryId + ") was set for activity (id=" + activityId + ")");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            throw new DBException("MysqlRequestDAO: setActivityCategories was failed.", e);
        }
    }

    @Override
    public List<Integer> withCategories(Connection con, int activityId) throws DBException {
        List<Integer> categoryList;
        try (PreparedStatement prst = con.prepareStatement(SELECT_ACTIVITY_CATEGORIES)) {
            categoryList = new ArrayList<>();
            int c = 0;
            prst.setInt(++c, activityId);
            try (ResultSet rs = prst.executeQuery()) {
                while (rs.next())
                    categoryList.add(rs.getInt(COL_ACT_CAT_CATEGORY_ID));
            }
            logger.info("Id selection of activity (id=" + activityId + ") categories was successful");
            logger.info("Count of ids: " + categoryList.size());
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            throw new DBException("MysqlRequestDAO: withCategories was failed.", e);
        }
        return categoryList;
    }

    public void updateActivityStatus(Connection con, Activity activity, Activity.Status status) throws DBException {
        try (PreparedStatement prst = con.prepareStatement(UPDATE_ACTIVITY_STATUS)) {
            int c = 0;
            prst.setString(++c, status.getValue());
            prst.setInt(++c, activity.getId());
            prst.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            throw new DBException("MysqlRequestDAO: updateActivityStatus was failed.", e);
        }
    }

    @Override
    public synchronized void confirmAdd(int requestId, int activityId, int userId) throws DBException {
        Connection con = null;
        PreparedStatement prst = null;
        try {
            con = factory.getConnection();
            con.setAutoCommit(false);
            prst = con.prepareStatement(REQUEST_CONFIRM);
            int c = 0;
            prst.setInt(++c, requestId);
            prst.executeUpdate();
            logger.info("Request (id=" + requestId + ") for add was confirmed");
            prst = con.prepareStatement(UPDATE_ACTIVITY_STATUS);
            c = 0;
            prst.setString(++c, Activity.Status.BY_USER.getValue());
            prst.setInt(++c, activityId);
            prst.executeUpdate();
            logger.info("Activity (id=" + activityId + ") status was updated");
            prst = con.prepareStatement(INSERT_USER_ACTIVITY);
            c = 0;
            prst.setInt(++c, userId);
            prst.setInt(++c, activityId);
            prst.execute();
            logger.info("Creator (id=" + userId + ") was added to activity");
            prst = con.prepareStatement(UPDATE_ACTIVITY_PEOPLE_COUNT);
            c = 0;
            prst.setInt(++c, activityId);
            prst.setInt(++c, activityId);
            prst.executeUpdate();
            logger.info("Activity (id=" + activityId + ") people count was updated");
            prst = con.prepareStatement(UPDATE_USER_ACTIVITY_COUNT);
            c = 0;
            prst.setInt(++c, userId);
            prst.setInt(++c, userId);
            prst.executeUpdate();
            logger.info("Creator (id=" + userId + ") activity count was updated");
            con.commit();
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            factory.rollback(con);
            throw new DBException("MysqlRequestDAO: confirmAdd was failed.", e);
        } finally {
            factory.closeResource(prst);
            factory.closeResource(con);
        }
    }

    @Override
    public synchronized void confirmRemove(int requestId, int activityId) throws DBException {
        Connection con = null;
        PreparedStatement prst = null;
        try {
            con = factory.getConnection();
            con.setAutoCommit(false);
            prst = con.prepareStatement(REQUEST_CONFIRM);
            int c = 0;
            prst.setInt(++c, requestId);
            prst.executeUpdate();
            logger.info("Request (id=" + requestId + ") for remove was confirmed");
            prst = con.prepareStatement(UPDATE_ACTIVITY_STATUS);
            c = 0;
            prst.setString(++c, Activity.Status.DEL_CONFIRMED.getValue());
            prst.setInt(++c, activityId);
            prst.executeUpdate();
            logger.info("Activity (id=" + activityId + ") status was updated");
            List<User> userList = new ArrayList<>();
            prst = con.prepareStatement(SELECT_ALL_USERS_ACTIVITY);
            c = 0;
            prst.setInt(++c, activityId);
            try (ResultSet rs = prst.executeQuery()) {
                while (rs.next()) {
                    User user = new User();
                    user.setId(rs.getInt(COL_USERS_ID));
                    userList.add(user);
                }
            }
            logger.info("Selection of participating users in activity (id=" + activityId + ") was successful");
            prst = con.prepareStatement(DELETE_USERS_ACTIVITY);
            c = 0;
            prst.setInt(++c, activityId);
            prst.execute();
            logger.info("Users from activity (id=" + activityId + ") was removed");
            prst = con.prepareStatement(UPDATE_USER_ACTIVITY_COUNT);
            for (User user : userList) {
                c = 0;
                prst.setInt(++c, user.getId());
                prst.setInt(++c, user.getId());
                prst.executeUpdate();
            }
            logger.info("Activity count of earlier participating users was updated");
            con.commit();
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            factory.rollback(con);
            throw new DBException("MysqlRequestDAO: confirmRemove was failed.", e);
        } finally {
            factory.closeResource(prst);
            factory.closeResource(con);
        }
    }

    @Override
    public synchronized void declineAdd(int requestId, int activityId) throws DBException {
        Connection con = null;
        PreparedStatement prst = null;
        try {
            con = factory.getConnection();
            con.setAutoCommit(false);
            prst = con.prepareStatement(REQUEST_DECLINE);
            int c = 0;
            prst.setInt(++c, requestId);
            prst.executeUpdate();
            logger.info("Request (id=" + requestId + ") for add was declined");
            prst = con.prepareStatement(UPDATE_ACTIVITY_STATUS);
            c = 0;
            prst.setString(++c, Activity.Status.ADD_DECLINED.getValue());
            prst.setInt(++c, activityId);
            prst.executeUpdate();
            logger.info("Activity (id=" + activityId + ") status was updated");
            con.commit();
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            factory.rollback(con);
            throw new DBException("MysqlRequestDAO: declineAdd was failed.", e);
        } finally {
            factory.closeResource(prst);
            factory.closeResource(con);
        }
    }

    @Override
    public synchronized void declineRemove(int requestId, int activityId) throws DBException {
        Connection con = null;
        PreparedStatement prst = null;
        try {
            con = factory.getConnection();
            con.setAutoCommit(false);
            prst = con.prepareStatement(REQUEST_DECLINE);
            int c = 0;
            prst.setInt(++c, requestId);
            prst.executeUpdate();
            logger.info("Request (id=" + requestId + ") for remove was declined");
            prst = con.prepareStatement(UPDATE_ACTIVITY_STATUS);
            c = 0;
            prst.setString(++c, Activity.Status.BY_USER.getValue());
            prst.setInt(++c, activityId);
            prst.executeUpdate();
            logger.info("Activity (id=" + activityId + ") status was updated");
            con.commit();
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            factory.rollback(con);
            throw new DBException("MysqlRequestDAO: declineRemove was failed.", e);
        } finally {
            factory.closeResource(prst);
            factory.closeResource(con);
        }
    }

    @Override
    public synchronized void cancelAdd(int requestId, int activityId, User authUser) throws DBException {
        try (Connection con = factory.getConnection();
             PreparedStatement prst = con.prepareStatement(DELETE_ACTIVITY_BY_USER)) {
            int c = 0;
            prst.setInt(++c, activityId);
            prst.setInt(++c, authUser.getId());
            prst.execute();
            logger.info("Request (id=" + requestId + ") for add was canceled successfully");
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            throw new DBException("MysqlRequestDAO: cancelAdd was failed.", e);
        }
    }

    @Override
    public synchronized void cancelRemove(int requestId, int activityId, User authUser) throws DBException {
        Connection con = null;
        PreparedStatement prst = null;
        try {
            con = factory.getConnection();
            con.setAutoCommit(false);
            prst = con.prepareStatement(UPDATE_ACTIVITY_STATUS);
            int c = 0;
            prst.setString(++c, Activity.Status.BY_USER.getValue());
            prst.setInt(++c, activityId);
            prst.executeUpdate();
            prst = con.prepareStatement(DELETE_REQUEST);
            c = 0;
            prst.setInt(++c, requestId);
            prst.execute();
            logger.info("Request (id=" + requestId + ") for remove was canceled successfully");
            con.commit();
        } catch (SQLException e) {
            e.printStackTrace();
            logger.error(e);
            factory.rollback(con);
            throw new DBException("MysqlRequestDAO: cancelRemove was failed.", e);
        } finally {
            factory.closeResource(prst);
            factory.closeResource(con);
        }
    }

    /**
     * Getting id of inserted activity
     * @param prst prepared statement
     * @return id of inserted activity
     * @throws SQLException if something went wrong while getting element
     */
    private int getInsertedActivityId(PreparedStatement prst) throws SQLException {
        int activityId = 0;
        try (ResultSet rs = prst.getGeneratedKeys()) {
            if (rs.next())
                activityId = rs.getInt(1);
        }
        return activityId;
    }

    /**
     * Request Mapper. Setting received request
     */
    private static class RequestMapper implements EntityMapper<Request> {
        @Override
        public Request mapRow(ResultSet rs) {
            try {
                Request request = new Request();
                request.setId(rs.getInt(COL_REQUESTS_ID));
                request.setActivityId(rs.getInt(COL_REQUESTS_ACTIVITY_ID));
                request.setStatus(Request.Status.get(rs.getString(COL_REQUESTS_STATUS)));
                request.setForDelete(rs.getBoolean(COL_REQUESTS_FOR_DELETE));
                request.setCreateTime(new Date(rs.getTimestamp(COL_REQUESTS_CREATE_TIME).getTime()));
                request.setActivity(new Activity(rs.getInt(COL_ACTIVITIES_ID), rs.getString(COL_ACTIVITIES_NAME),
                        Activity.Status.get(rs.getString(COL_ACTIVITIES_STATUS))));
                request.setCreator(new User(rs.getInt(COL_USERS_ID), rs.getString(COL_USERS_LAST_NAME),
                        rs.getString(COL_USERS_FIRST_NAME), rs.getString(COL_USERS_IMAGE)));
                return request;
            } catch (SQLException e) {
                throw new IllegalArgumentException();
            }
        }

        public Request mapActivity(ResultSet rs) {
            try {
                Request request = new Request();
                request.setId(rs.getInt(COL_REQUESTS_ID));
                request.setActivityId(rs.getInt(COL_REQUESTS_ACTIVITY_ID));
                request.setForDelete(rs.getBoolean(COL_REQUESTS_FOR_DELETE));
                request.setStatus(Request.Status.get(rs.getString(COL_REQUESTS_STATUS)));
                Activity activity = new Activity();
                activity.setId(rs.getInt(COL_ACTIVITIES_ID));
                activity.setName(rs.getString(COL_ACTIVITIES_NAME));
                activity.setDescription(rs.getString(COL_ACTIVITIES_DESCRIPTION));
                activity.setImage(rs.getString(COL_ACTIVITIES_IMAGE));
                activity.setCreatorId(rs.getInt(COL_ACTIVITIES_CREATOR_ID));
                activity.setStatus(Activity.Status.get(rs.getString(COL_ACTIVITIES_STATUS)));
                request.setActivity(activity);
                return request;
            } catch (SQLException e) {
                throw new IllegalArgumentException();
            }
        }

        /**
         * Setting status of request
         * @param request received request
         * @param status received status
         */
        private void setRequestStatus(Request request, String status) {
            if (status.equals(Request.Status.WAITING.toString()))
                request.setStatus(Request.Status.WAITING);
            else if (status.equals(Request.Status.CONFIRMED.toString()))
                request.setStatus(Request.Status.CONFIRMED);
        }
    }
}
