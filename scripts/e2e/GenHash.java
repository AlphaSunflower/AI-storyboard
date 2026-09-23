import com.lambdaworks.crypto.SCryptUtil;

/** 一次性工具：生成与网关 AdminUserServiceImpl 一致的 scrypt MCF 哈希（N=16384,r=8,p=1） */
public class GenHash {
    public static void main(String[] args) throws Exception {
        System.out.print(SCryptUtil.scrypt(args[0], 16384, 8, 1));
    }
}
