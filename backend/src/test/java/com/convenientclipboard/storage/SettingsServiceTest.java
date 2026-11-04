package com.convenientclipboard.storage;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettingsServiceTest {
    private static final long MIB=1048576L;
    private static final long CAPACITY=10737418240L;
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final StorageCoordinator coordinator=mock(StorageCoordinator.class);
    private final LocalStorage local=mock(LocalStorage.class);
    private final ResultSet row=mock(ResultSet.class);
    private Long storedLimit;
    private long storedCapacity=CAPACITY;
    private int storedThreshold=50;
    private SettingsService service;
    private AppProperties properties(long bootstrap) { return new AppProperties("http://localhost",Path.of("data"),bootstrap,"j".repeat(32),"admin","password123","k".repeat(32)); }
    @BeforeEach void setup() throws Exception {
        when(coordinator.locked(any())).thenAnswer(invocation->((Supplier<?>)invocation.getArgument(0)).get());
        when(row.getLong("local_capacity_bytes")).thenAnswer(ignored->storedCapacity);
        when(row.getInt("offload_threshold")).thenAnswer(ignored->storedThreshold);
        when(row.getObject("max_upload_bytes",Long.class)).thenAnswer(ignored->storedLimit);
        when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<StorageSettings>>any())).thenAnswer(invocation->invocation.<RowMapper<StorageSettings>>getArgument(1).mapRow(row,0));
        when(jdbc.update(anyString(),anyLong(),anyInt(),anyLong())).thenAnswer(invocation->{
            storedCapacity=invocation.getArgument(1);storedThreshold=invocation.getArgument(2);storedLimit=invocation.getArgument(3);return 1;
        });
        service=new SettingsService(jdbc,coordinator,local,properties(2*MIB));
    }
    @Test void nullableMigrationValuePreservesExistingEnvironmentUntilFirstSave() {
        assertEquals(2*MIB,service.get().maxUploadBytes());
        assertNull(storedLimit,"Read-only config requests must not silently initialize the database");
        verify(jdbc,never()).update(anyString(),anyLong(),anyInt(),anyLong());
    }
    @Test void oldSettingsRequestFreezesBootstrapAndRestartsRespectPersistedValue() throws Exception {
        StorageSettings oldRequest=new ObjectMapper().readValue("{\"localCapacityBytes\":10737418240,\"offloadThreshold\":60}",StorageSettings.class);
        assertNull(oldRequest.maxUploadBytes());
        StorageSettings saved=service.save(oldRequest);
        assertEquals(2*MIB,saved.maxUploadBytes());assertEquals(2*MIB,storedLimit);assertEquals(60,saved.offloadThreshold());
        SettingsService restarted=new SettingsService(jdbc,coordinator,local,properties(8*MIB));
        assertEquals(2*MIB,restarted.get().maxUploadBytes(),"An environment change must not overwrite the saved limit");
    }
    @Test void omittingLimitOrSendingNullPreservesCurrentDatabaseValue() throws Exception {
        storedLimit=3*MIB;
        assertEquals(3*MIB,service.save(new StorageSettings(CAPACITY,50)).maxUploadBytes());
        StorageSettings explicitNull=new ObjectMapper().readValue("{\"localCapacityBytes\":10737418240,\"offloadThreshold\":60,\"maxUploadBytes\":null}",StorageSettings.class);
        assertEquals(3*MIB,service.save(explicitNull).maxUploadBytes());assertEquals(3*MIB,storedLimit);
    }
    @Test void changesAndBothInclusiveBoundariesArePersisted() {
        assertEquals(MIB,service.save(new StorageSettings(CAPACITY,50,MIB)).maxUploadBytes());
        assertEquals(StorageSettings.MAX_UPLOAD_BYTES,service.save(new StorageSettings(CAPACITY,50,StorageSettings.MAX_UPLOAD_BYTES)).maxUploadBytes());
        assertEquals(2*MIB,service.save(new StorageSettings(CAPACITY,50,2*MIB)).maxUploadBytes());
        assertEquals(2*MIB,storedLimit);
    }
    @Test void invalidValuesCannotBePersistedEvenOutsideControllerValidation() {
        for(long invalid:new long[]{0,-1,MIB-1,StorageSettings.MAX_UPLOAD_BYTES+1}) {
            ApiException failure=assertThrows(ApiException.class,()->service.save(new StorageSettings(CAPACITY,50,invalid)));
            assertEquals(HttpStatus.BAD_REQUEST,failure.status());assertNull(storedLimit);
        }
        verify(jdbc,never()).update(anyString(),anyLong(),anyInt(),anyLong());
    }
    @Test void requestValidationAllowsMissingValueButRejectsOutOfRangeValues() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertTrue(validator.validate(new StorageSettings(CAPACITY,50)).isEmpty());
            for(long invalid:new long[]{0,-1,MIB-1,StorageSettings.MAX_UPLOAD_BYTES+1})
                assertTrue(validator.validate(new StorageSettings(CAPACITY,50,invalid)).stream().anyMatch(violation->violation.getPropertyPath().toString().equals("maxUploadBytes")));
        }
    }
}
