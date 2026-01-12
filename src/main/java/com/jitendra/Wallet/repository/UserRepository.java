package com.jitendra.Wallet.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.jitendra.Wallet.entity.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
}
