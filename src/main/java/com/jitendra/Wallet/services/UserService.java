package com.jitendra.Wallet.services;

import org.springframework.stereotype.Service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.jitendra.Wallet.entity.User;
import com.jitendra.Wallet.repository.UserRepository;
import com.jitendra.Wallet.dto.UserRequestDTO;
import com.jitendra.Wallet.dto.UserResponseDTO;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {
    
    private final UserRepository userRepository;

    public UserResponseDTO createUser(UserRequestDTO userRequestDTO) {
        User user = new User();
        user.setName(userRequestDTO.getName());
        user.setEmail(userRequestDTO.getEmail());
        
        User savedUser = userRepository.save(user);
        log.info("User created successfully with id: {} in database shardwallet{}", savedUser.getId(), (savedUser.getId() % 2+1));
        
        return new UserResponseDTO(savedUser.getId(), savedUser.getName(), savedUser.getEmail());
    }
}
