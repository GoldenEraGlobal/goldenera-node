package global.goldenera.node.explorer.api.v1.common;

import static lombok.AccessLevel.PRIVATE;

import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import global.goldenera.node.explorer.api.v1.common.dtos.SearchDtoV1;
import global.goldenera.node.explorer.api.v1.common.mappers.CommonMapper;
import global.goldenera.node.explorer.enums.ExSearchEntityType;
import global.goldenera.node.explorer.services.core.ExCommonCoreService;
import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.exceptions.GEValidationException;
import global.goldenera.node.shared.security.ExplorerApiSecurity;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "api/explorer/v1/common")
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "ge.general", name = "explorer-enable", havingValue = "true", matchIfMissing = true)
public class CommonApiV1 {

        ExCommonCoreService exCommonCoreService;
        CommonMapper commonMapper;

        @GetMapping("search")
        @ExplorerApiSecurity(ApiKeyPermission.READ_SEARCH)
        public SearchDtoV1 apiV1CommonSearch(
                        @RequestParam(required = true) String query,
                        @RequestParam(required = false) Set<ExSearchEntityType> searchIn) {
                if (query == null) {
                        throw new GEValidationException("Query is null");
                }
                return commonMapper.map(exCommonCoreService.search(query, searchIn));
        }
}
