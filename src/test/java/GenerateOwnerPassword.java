import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class GenerateOwnerPassword {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String password = "owner123";
        String hash = encoder.encode(password);
        System.out.println("Password: " + password);
        System.out.println("BCrypt Hash: " + hash);
        System.out.println("Hash length: " + hash.length());
    }
}
