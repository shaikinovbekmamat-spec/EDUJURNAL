import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class CreateOwnerAccount {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/tkrpo";
        String user = "postgres";
        String password = "5555";

        // BCrypt hash для пароля "owner123"
        String passwordHash = "$2a$10$N9qo8uLOickgx2ZMRZoMye1JdJAXmLKT.pqJBu1xOyQBmLiRKpWaO";

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            System.out.println("Подключение к базе данных успешно!");

            // Проверяем, существует ли уже owner
            String checkSql = "SELECT id, login, role FROM users WHERE role = 'OWNER'";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql);
                 ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    System.out.println("Owner аккаунт уже существует:");
                    System.out.println("ID: " + rs.getLong("id"));
                    System.out.println("Login: " + rs.getString("login"));
                    System.out.println("Role: " + rs.getString("role"));
                    return;
                }
            }

            // Создаем owner аккаунт
            String insertSql = "INSERT INTO users (login, password, role, enabled, full_name, school_id) " +
                             "VALUES (?, ?, 'OWNER', true, 'Владелец системы', NULL)";
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                insertStmt.setString(1, "owner");
                insertStmt.setString(2, passwordHash);
                int rowsAffected = insertStmt.executeUpdate();
                System.out.println("Owner аккаунт создан успешно! Затронуто строк: " + rowsAffected);
            }

            // Выводим информацию о созданном аккаунте
            try (PreparedStatement stmt = conn.prepareStatement(checkSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    System.out.println("\nИнформация о созданном аккаунте:");
                    System.out.println("ID: " + rs.getLong("id"));
                    System.out.println("Login: owner");
                    System.out.println("Password: owner123");
                    System.out.println("Role: " + rs.getString("role"));
                }
            }

        } catch (SQLException e) {
            System.err.println("Ошибка при работе с базой данных:");
            e.printStackTrace();
        }
    }
}
