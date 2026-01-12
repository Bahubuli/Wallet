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

    public UserResponseDTO getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
        log.info("User fetched with id: {}", id);
        return new UserResponseDTO(user.getId(), user.getName(), user.getEmail());
    }

    public UserResponseDTO getUserByName(String name) {
        User user = userRepository.findByName(name)
                .orElseThrow(() -> new RuntimeException("User not found with name: " + name));
        log.info("User fetched with name: {}", name);
        return new UserResponseDTO(user.getId(), user.getName(), user.getEmail());
    }
}
